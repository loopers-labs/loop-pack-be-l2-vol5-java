package com.loopers.application.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.AdminProductService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.payment.HttpTestPgClient;
import com.loopers.infrastructure.payment.PgHttpTimeouts;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * 실제 HTTP와 MySQL의 내부 신규 발송 경계다. 고객 confirm 재요청/카드 비교 계약의 완료 증거는 아니다.
 */
@SpringBootTest
@Timeout(20)
class OrderPaymentHttpBoundaryIntegrationTest {
    private static final String ACCOUNT = "135135";
    private static final String CARD = "0000-0000-0000-0000";

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    @Autowired private OrderService orders;
    @Autowired private AdminProductService adminProducts;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private OrderPaymentStockService stocks;

    private LocalTestPg pg;
    private HttpTestPgClient client;
    private TransactionCheckingGateway gateway;
    private OrderPaymentCoordinator coordinator;
    private boolean workersTerminated;

    @BeforeEach
    void setUp() throws Exception {
        initializer.initialize();
        assertThat(AopUtils.isAopProxy(stocks)).as("실제 TX A/B Spring 프록시").isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        pg = new LocalTestPg();
        client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), new ObjectMapper(), PgHttpTimeouts.defaults());
        gateway = new TransactionCheckingGateway(client);
        coordinator = new OrderPaymentCoordinator(stocks, gateway);
        workersTerminated = true;
    }

    @AfterEach
    void tearDown() throws Exception {
        OrderPaymentStockService target = AopTestUtils.getUltimateTargetObject(stocks);
        reset(target);
        if (pg != null) {
            pg.releaseResponses();
        }
        if (client != null) {
            client.close();
        }
        if (pg != null) {
            pg.close();
        }
        assertThat(workersTerminated).as("worker 종료 뒤에만 DB 정리").isTrue();
        cleanup.truncateAllTables();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAnExistingCallerTransactionBeforeAnyWriteOrHttp(boolean refresh) {
        Fixture fixture = draft();
        if (refresh) {
            stocks.prepareNew("alice", fixture.orderId(), fixture.attemptKey(), ACCOUNT, fixture.providerOrderId(), "TEST", CARD);
        }
        var before = storedState();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            if (refresh) {
                coordinator.refreshOnce("alice", fixture.orderId(), fixture.attemptKey());
            } else {
                start(fixture);
            }
        })).isInstanceOf(IllegalStateException.class);

        assertThat(storedState()).isEqualTo(before);
        assertThat(pg.createCalls()).isZero();
        assertThat(pg.lookupCalls()).isZero();
        assertThat(pg.transactionCount()).isZero();
        assertThat(gateway.invocations()).isZero();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void reservationIsCommittedAndBusinessLocksAreReleasedWhileRealHttpIsWaiting() throws Exception {
        Fixture fixture = draft();
        pg.nextCreateMode(LocalTestPg.CreateMode.HOLD_RESPONSE_AFTER_CREATE);
        ExecutorService workers = workers();
        try {
            var payment = workers.submit(() -> start(fixture));
            assertThat(pg.awaitCreate(Duration.ofSeconds(2))).as("실제 PG가 거래를 생성함").isTrue();
            assertThat(payment.isDone()).as("PG 응답은 아직 대기 중").isFalse();

            // 요청 worker와 다른 DB 연결의 조회다. TX A 커밋 전이면 이 저장 사실을 읽을 수 없다.
            assertState(fixture, "PAYMENT_PENDING", "HELD", "NOT_OBSERVED", 3);
            assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getSendStartedAt()).isNotNull();
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);

            var administration = workers.submit(() -> adminProducts.changeStock(UserRole.ADMIN, fixture.productId(), 10));
            administration.get(2, TimeUnit.SECONDS);
            assertThat(payment.isDone()).as("관리자 변경은 PG 응답 해제 전에 완료").isFalse();
            assertThat(stock(fixture)).isEqualTo(10);
            pg.releaseResponses();

            var result = payment.get(3, TimeUnit.SECONDS);
            assertThat(result.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(result.paymentStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertState(fixture, "PAYMENT_PENDING", "HELD", "PENDING", 10);
            assertThat(gateway.invocations()).isEqualTo(1);
            assertThat(balance()).isZero();
        } finally {
            pg.releaseResponses();
            finish(workers);
        }
    }

    @Test
    void concurrentInternalNewStartsCommitOneReservationAndSendOneRealPost() throws Exception {
        Fixture fixture = draft();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService workers = workers();
        try {
            Callable<Outcome> task = () -> {
                ready.countDown();
                if (!go.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent start gate timed out");
                }
                try {
                    return new Outcome(start(fixture), null);
                } catch (RuntimeException failure) {
                    return new Outcome(null, failure);
                }
            };
            var first = workers.submit(task);
            var second = workers.submit(task);
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var outcomes = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(value -> value.failure() == null).toList()).singleElement()
                .satisfies(value -> assertThat(value.result().paymentStatus()).isEqualTo(PaymentAttemptStatus.PENDING));
            assertThat(outcomes.stream().filter(value -> value.failure() != null).toList()).singleElement()
                .satisfies(value -> assertThat(value.failure()).isInstanceOf(IllegalStateException.class));
            assertState(fixture, "PAYMENT_PENDING", "HELD", "PENDING", 3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class)).isEqualTo(1L);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(gateway.invocations()).isEqualTo(1);
            assertThat(balance()).isZero();
        } finally {
            go.countDown();
            finish(workers);
        }
    }

    @Test
    void actualSqlFailureBeforeTxACommitRollsBackPreparationAndNeverCallsPg() {
        Fixture fixture = draft();
        var before = storedState();
        AtomicBoolean written = new AtomicBoolean();
        IllegalStateException injected = new IllegalStateException("test-only failure after TX A SQL");
        OrderPaymentStockService target = AopTestUtils.getUltimateTargetObject(stocks);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            entityManager.flush();
            assertState(fixture, "PAYMENT_PENDING", "HELD", "NOT_OBSERVED", 3);
            assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getSendStartedAt()).isNotNull();
            assertThat(storedState()).isNotEqualTo(before);
            written.set(true);
            throw injected;
        }).when(target).prepareNew("alice", fixture.orderId(), fixture.attemptKey(), ACCOUNT, fixture.providerOrderId(), "TEST", CARD);

        assertThatThrownBy(() -> start(fixture)).isSameAs(injected);

        assertThat(written).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(storedState()).isEqualTo(before);
        assertThat(pg.createCalls()).isZero();
        assertThat(pg.transactionCount()).isZero();
        assertThat(gateway.invocations()).isZero();
        reset(target);
        start(fixture);
        assertState(fixture, "PAYMENT_PENDING", "HELD", "PENDING", 3);
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentTransaction.Status.class, names = {"SUCCESS", "FAILED"})
    void txBFailureDoesNotUndoExternalResultAndLaterAuthoritativeGetRecoversWithoutAnotherPost(
        PaymentTransaction.Status externalStatus) {
        Fixture fixture = draft();
        start(fixture);
        String transactionKey = attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey();
        pg.setStatus(transactionKey, externalStatus.name());
        var before = storedState();
        AtomicBoolean written = new AtomicBoolean();
        IllegalStateException injected = new IllegalStateException("test-only failure after TX B SQL");
        OrderPaymentStockService target = AopTestUtils.getUltimateTargetObject(stocks);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            entityManager.flush();
            assertTerminal(fixture, externalStatus);
            assertThat(storedState()).isNotEqualTo(before);
            written.set(true);
            throw injected;
        }).when(target).apply(eq(fixture.orderId()), eq(fixture.attemptKey()), any(PaymentTransaction.class));

        assertThatThrownBy(() -> coordinator.refreshOnce("alice", fixture.orderId(), fixture.attemptKey()))
            .isSameAs(injected);

        assertThat(written).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(storedState()).isEqualTo(before);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(client.findByTransactionKey(transactionKey).transactions()).singleElement()
            .satisfies(transaction -> assertThat(transaction.status()).isEqualTo(externalStatus));

        reset(target);
        // 조율 객체를 다시 만들어도 저장된 시도/거래로 회복하며 카드와 새 POST가 필요 없다.
        coordinator = new OrderPaymentCoordinator(stocks, gateway);
        var recovered = coordinator.refreshOnce("alice", fixture.orderId(), fixture.attemptKey());
        assertThat(recovered.paymentStatus()).isEqualTo(PaymentAttemptStatus.valueOf(externalStatus.name()));
        assertTerminal(fixture, externalStatus);
        var terminal = storedState();
        int lookupCalls = pg.lookupCalls();
        coordinator.refreshOnce("alice", fixture.orderId(), fixture.attemptKey());
        assertThat(storedState()).isEqualTo(terminal);
        assertThat(pg.lookupCalls()).as("이미 종결된 시도는 추가 권위 조회도 하지 않음").isEqualTo(lookupCalls);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(balance()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,false", "FAILED,false", "SUCCESS,true", "FAILED,true"})
    void latePendingOrActualTimeoutCannotReplaceAnAlreadyCommittedTerminalResult(
        PaymentTransaction.Status terminalStatus, boolean timeout) throws Exception {
        Fixture fixture = draft();
        if (timeout) {
            client.close();
            client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), new ObjectMapper(),
                new PgHttpTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(200),
                    Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(3)));
            gateway = new TransactionCheckingGateway(client);
        }
        CountDownLatch terminalCommitted = new CountDownLatch(1);
        DeliveryGateGateway gatedGateway = new DeliveryGateGateway(gateway, terminalCommitted);
        coordinator = new OrderPaymentCoordinator(stocks, gatedGateway);
        pg.nextCreateMode(LocalTestPg.CreateMode.HOLD_RESPONSE_AFTER_CREATE);
        ExecutorService workers = workers();
        try {
            var firstRequest = workers.submit(() -> start(fixture));
            assertThat(pg.awaitCreate(Duration.ofSeconds(2))).isTrue();
            String transactionKey = client.findByProviderOrderId(fixture.providerOrderId()).transactions()
                .getFirst().transactionKey();
            pg.setStatus(transactionKey, terminalStatus.name());
            PaymentTransaction authoritative = client.findByTransactionKey(transactionKey).transactions().getFirst();

            // callback 자체를 구현한 테스트는 아니다. 별도의 권위 조회 결과가 같은 TX B에 먼저 도착한 경합이다.
            stocks.apply(fixture.orderId(), fixture.attemptKey(), authoritative);
            assertTerminal(fixture, terminalStatus);
            var committed = storedState();
            terminalCommitted.countDown();
            if (!timeout) {
                // fixture가 생성 시점에 만든 PENDING 응답을 늦게 전달한다.
                pg.releaseResponses();
            }

            var returned = firstRequest.get(4, TimeUnit.SECONDS);

            assertThat(returned.orderStatus()).isEqualTo(terminalStatus == PaymentTransaction.Status.SUCCESS
                ? OrderStatus.CONFIRMED : OrderStatus.PAYMENT_FAILED);
            assertThat(returned.paymentStatus()).isEqualTo(PaymentAttemptStatus.valueOf(terminalStatus.name()));
            assertThat(storedState()).isEqualTo(committed);
            if (timeout) {
                assertThat(gatedGateway.observed().kind()).isEqualTo(PaymentGatewayResult.Kind.UNKNOWN);
                assertThat(gatedGateway.observed().failure()).isEqualTo(PaymentGatewayResult.Failure.TIMEOUT);
            } else {
                assertThat(gatedGateway.observed().transactions()).singleElement()
                    .satisfies(transaction -> assertThat(transaction.status()).isEqualTo(PaymentTransaction.Status.PENDING));
            }
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(balance()).isZero();
        } finally {
            terminalCommitted.countDown();
            pg.releaseResponses();
            finish(workers);
        }
    }

    @Test
    void rejectionRollbackAndLostInMemoryEvidenceCannotBeReconstructedFromAnEmptyLookup() throws Exception {
        Fixture fixture = draft();
        pg.nextCreateMode(LocalTestPg.CreateMode.REJECT_BEFORE_CREATE);
        AtomicReference<Map<String, List<Map<String, Object>>>> prepared = new AtomicReference<>();
        AtomicBoolean written = new AtomicBoolean();
        IllegalStateException injected = new IllegalStateException("test-only failure after rejection TX B SQL");
        OrderPaymentStockService target = AopTestUtils.getUltimateTargetObject(stocks);
        doAnswer(invocation -> {
            prepared.set(storedState());
            invocation.callRealMethod();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            entityManager.flush();
            assertState(fixture, "PAYMENT_FAILED", "RELEASED", "FAILED", 5);
            assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey()).isNull();
            written.set(true);
            throw injected;
        }).when(target).rejectBeforeCreate(fixture.orderId(), fixture.attemptKey(), ACCOUNT,
            fixture.providerOrderId(), 200L);

        assertThatThrownBy(() -> start(fixture)).isSameAs(injected);

        assertThat(written).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(storedState()).isEqualTo(prepared.get());
        assertState(fixture, "PAYMENT_PENDING", "HELD", "NOT_OBSERVED", 3);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isZero();

        reset(target);
        client.close();
        client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), new ObjectMapper(), PgHttpTimeouts.defaults());
        gateway = new TransactionCheckingGateway(client);
        coordinator = new OrderPaymentCoordinator(stocks, gateway);
        var result = coordinator.refreshOnce("alice", fixture.orderId(), fixture.attemptKey());

        assertThat(result.paymentStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertState(fixture, "PAYMENT_PENDING", "HELD", "UNKNOWN", 3);
        assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getTransactionKey()).isNull();
        assertThat(pg.lookupCalls()).isEqualTo(1);
        assertThat(pg.requests().getLast().rawQuery()).isEqualTo("orderId=" + fixture.providerOrderId());
        var unresolved = storedState();
        coordinator.refreshOnce("alice", fixture.orderId(), fixture.attemptKey());
        assertThat(storedState()).isEqualTo(unresolved);
        assertThat(pg.lookupCalls()).isEqualTo(2);
        assertThat(pg.createCalls()).as("빈 목록으로 미발송을 단정하거나 생성 재시도하지 않음").isEqualTo(1);
        assertThat(pg.transactionCount()).isZero();
        assertThat(balance()).isZero();
    }

    private Fixture draft() {
        Product product = products.save(new Product(brands.save(new Brand("HTTP 경계 브랜드")), "상품", 100, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(product.getId(), orderId, UUID.randomUUID(), "BOUNDARY-ORDER-" + orderId);
    }

    private OrderPaymentStockService.AppliedPayment start(Fixture fixture) {
        return coordinator.startNew("alice", fixture.orderId(), fixture.attemptKey(), fixture.providerOrderId(), "TEST", CARD);
    }

    private ExecutorService workers() {
        workersTerminated = false;
        return Executors.newFixedThreadPool(2);
    }

    private void finish(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        workersTerminated = workers.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(workersTerminated).isTrue();
    }

    private void assertTerminal(Fixture fixture, PaymentTransaction.Status status) {
        boolean success = status == PaymentTransaction.Status.SUCCESS;
        assertState(fixture, success ? "CONFIRMED" : "PAYMENT_FAILED", success ? "CONSUMED" : "RELEASED",
            status.name(), success ? 3 : 5);
        Long paidAmount = jdbc.queryForObject("SELECT paid_amount FROM `order` WHERE id=?", Long.class, fixture.orderId());
        if (success) {
            assertThat(paidAmount).isEqualTo(200L);
        } else {
            assertThat(paidAmount).isNull();
        }
    }

    private void assertState(Fixture fixture, String orderStatus, String reservationStatus, String paymentStatus, int stock) {
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId()))
            .isEqualTo(orderStatus);
        assertThat(jdbc.queryForList("SELECT reservation_status FROM order_item WHERE order_id=?", String.class,
            fixture.orderId())).containsExactly(reservationStatus);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class,
            fixture.orderId())).isEqualTo(paymentStatus);
        assertThat(stock(fixture)).isEqualTo(stock);
    }

    private int stock(Fixture fixture) {
        return jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.productId());
    }

    private long balance() {
        return jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class);
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "attempts", jdbc.queryForList("SELECT id,order_id,HEX(attempt_key),provider_account,provider_order_id,"
                + "amount,status,send_started_at,transaction_key FROM payment_attempt ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "revisions", jdbc.queryForList("SELECT * FROM product_list_revision ORDER BY id"));
    }

    private record Fixture(long productId, long orderId, UUID attemptKey, String providerOrderId) { }

    private record Outcome(OrderPaymentStockService.AppliedPayment result, RuntimeException failure) { }

    /** 실제 HTTP 결과의 전달 순서만 통제한다. timeout 자체는 실제 client/socket에서 발생한다. */
    private static final class DeliveryGateGateway implements PaymentGateway {
        private final PaymentGateway delegate;
        private final CountDownLatch delivery;
        private final AtomicReference<PaymentGatewayResult> observed = new AtomicReference<>();

        private DeliveryGateGateway(PaymentGateway delegate, CountDownLatch delivery) {
            this.delegate = delegate;
            this.delivery = delivery;
        }

        @Override
        public PaymentGatewayResult create(PaymentCreateRequest request) {
            PaymentGatewayResult result = delegate.create(request);
            observed.set(result);
            try {
                if (!delivery.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Terminal result delivery gate timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the terminal result", interrupted);
            }
            return result;
        }

        @Override
        public PaymentGatewayResult findByTransactionKey(String transactionKey) {
            return delegate.findByTransactionKey(transactionKey);
        }

        @Override
        public PaymentGatewayResult findByProviderOrderId(String providerOrderId) {
            return delegate.findByProviderOrderId(providerOrderId);
        }

        private PaymentGatewayResult observed() {
            return observed.get();
        }
    }

    /** 실제 HTTP를 대신하지 않고 외부 호출 시점의 현재 스레드 TX 상태만 추가로 관찰한다. */
    private static final class TransactionCheckingGateway implements PaymentGateway {
        private final PaymentGateway delegate;
        private final AtomicInteger invocations = new AtomicInteger();

        private TransactionCheckingGateway(PaymentGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public PaymentGatewayResult create(PaymentCreateRequest request) {
            outsideTransaction();
            return delegate.create(request);
        }

        @Override
        public PaymentGatewayResult findByTransactionKey(String transactionKey) {
            outsideTransaction();
            return delegate.findByTransactionKey(transactionKey);
        }

        @Override
        public PaymentGatewayResult findByProviderOrderId(String providerOrderId) {
            outsideTransaction();
            return delegate.findByProviderOrderId(providerOrderId);
        }

        private void outsideTransaction() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            invocations.incrementAndGet();
        }

        private int invocations() {
            return invocations.get();
        }
    }
}
