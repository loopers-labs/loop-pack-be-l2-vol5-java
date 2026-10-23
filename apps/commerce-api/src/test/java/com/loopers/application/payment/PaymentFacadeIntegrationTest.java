package com.loopers.application.payment;

import com.loopers.CommerceApiApplication;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.CardNumber;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;
import com.loopers.domain.payment.PaymentCreation;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.DomainException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.pg.PgFixture;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentFacadeIntegrationTest {

    private static final PgFixture PG = PgFixture.start();

    private static final Long USER = 1L;
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final CardNumber CARD = CardNumber.of("0000-0000-0000-0000");

    private final PaymentFacade paymentFacade;
    private final PaymentTransactions paymentTransactions;
    private final PaymentGateway paymentGateway;
    private final PaymentSweeper paymentSweeper;
    private final PaymentUnresolvedMetrics unresolvedMetrics;
    private final CircuitBreaker lookupCircuit;
    private final MeterRegistry meterRegistry;
    private final PaymentRepository paymentRepository;
    private final OrderFacade orderFacade;
    private final ProductService productService;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final DataSource dataSource;
    private final DatabaseCleanUp databaseCleanUp;

    private Long coat;
    private int createsBefore;

    @Autowired
    PaymentFacadeIntegrationTest(
        PaymentFacade paymentFacade,
        PaymentTransactions paymentTransactions,
        PaymentGateway paymentGateway,
        PaymentSweeper paymentSweeper,
        PaymentUnresolvedMetrics unresolvedMetrics,
        CircuitBreaker lookupCircuit,
        MeterRegistry meterRegistry,
        PaymentRepository paymentRepository,
        OrderFacade orderFacade,
        ProductService productService,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        DataSource dataSource,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.paymentFacade = paymentFacade;
        this.paymentTransactions = paymentTransactions;
        this.paymentGateway = paymentGateway;
        this.paymentSweeper = paymentSweeper;
        this.unresolvedMetrics = unresolvedMetrics;
        this.lookupCircuit = lookupCircuit;
        this.meterRegistry = meterRegistry;
        this.paymentRepository = paymentRepository;
        this.orderFacade = orderFacade;
        this.productService = productService;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.dataSource = dataSource;
        this.databaseCleanUp = databaseCleanUp;
    }

    @DynamicPropertySource
    static void pgBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", PG::baseUrl);
        registry.add("pg.create-timeout", () -> "5s");
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(3_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(10));
        PG.reset();
        lookupCircuit.reset();
        createsBefore = PG.createCount();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Order place(int coats) {
        return orderFacade.place(new OrderCreateCommand(USER,
            List.of(new OrderCreateCommand.Line(coat, OrderQuantity.of(coats)))), NOW);
    }

    private Payment confirm(Long orderId, String attemptKey) {
        return paymentFacade.confirm(new PaymentConfirmCommand(
            USER, orderId, PaymentAttemptKey.of(attemptKey), CardType.TEST, CARD), NOW);
    }

    private static void lockOrder(Connection holder, Long orderId) throws Exception {
        try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE")) {
            lock.setLong(1, orderId);
            lock.executeQuery().close();
        }
    }

    private int creates() {
        return PG.createCount() - createsBefore;
    }

    private int lookups() {
        return (int) PG.receivedRequests().stream().filter(request -> request.method().equals("GET")).count();
    }

    private PgFixture.ReceivedRequest lastCreate() {
        return PG.receivedRequests().stream()
            .filter(request -> request.method().equals("POST"))
            .reduce((first, second) -> second)
            .orElseThrow();
    }

    @Nested
    @DisplayName("PAY-005, RSV-001: REQUESTED 를 commit 한 뒤 PG 생성을 한 번 보낸다")
    class Send {

        @DisplayName("DRAFT 주문을 confirm 하면 PG 에 우리 주문 번호와 저장된 금액으로 한 번 보낸다")
        @Test
        void sendsOnce() {
            Order order = place(2);

            Payment payment = confirm(order.getId(), "attempt-1");

            assertThat(creates()).isEqualTo(1);
            PgFixture.ReceivedRequest sent = lastCreate();
            assertThat(sent.body().get("orderId").asText()).isEqualTo(ProviderOrderId.of(order.getId()).value());
            assertThat(sent.body().get("amount").asLong()).isEqualTo(6_000);
            assertThat(sent.body().get("cardNo").asText()).isEqualTo(CARD.value());
            assertThat(paymentRepository.findByOrderId(order.getId())).map(Payment::getId).contains(payment.getId());
        }

        @DisplayName("같은 키를 다시 보내면 PG 를 다시 부르지 않는다")
        @Test
        void doesNotResendForSameKey() {
            Order order = place(2);
            Payment first = confirm(order.getId(), "attempt-1");

            Payment again = confirm(order.getId(), "attempt-1");

            assertThat(again.getId()).isEqualTo(first.getId());
            assertThat(creates()).isEqualTo(1);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }
    }

    @Nested
    @DisplayName("RESIL-010, PG-005, PG-010, PG-011: 생성 결과를 반영해 돌려주고, 결과를 기다리지 않는다")
    class ObserveCreate {

        @DisplayName("접수되면 PENDING 과 PG 가 만든 키를 돌려준다. 주문 PENDING, 재고는 묶인 채다")
        @Test
        void accepted() {
            Order order = place(2);

            Payment payment = confirm(order.getId(), "attempt-1");

            String created = PG.transactionsOf(ProviderOrderId.of(order.getId()).value()).stream()
                .map(PgFixture.Transaction::transactionKey)
                .max(String::compareTo)
                .orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey().value()).isEqualTo(created);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }

        @DisplayName("효과 0 건 거절이면 FAILED 를 돌려주고, 주문 FAILED, 재고를 돌려받는다")
        @Test
        void rejected() {
            Order order = place(2);
            PG.rejectNextCreate();

            Payment payment = confirm(order.getId(), "attempt-1");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(10));
        }

        @DisplayName("응답이 끊기면 UNKNOWN 을 돌려주고 다시 보내지 않는다. 재고는 묶인 채다")
        @Test
        void unknown() {
            Order order = place(2);
            PG.dropNextCreateResponse();

            Payment payment = confirm(order.getId(), "attempt-1");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(creates()).isEqualTo(1);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }

        @DisplayName("같은 키를 다시 보내면 반영된 상태를 돌려준다")
        @Test
        void repeatReturnsObservedState() {
            Order order = place(2);
            confirm(order.getId(), "attempt-1");

            Payment again = confirm(order.getId(), "attempt-1");

            assertThat(again.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(again.getTransactionKey()).isNotNull();
        }

        @DisplayName("confirm 은 PG 조회를 부르지 않는다. 결과는 callback, reconcile 이 맡는다")
        @Test
        void doesNotLookup() {
            Order order = place(2);
            int lookupsBefore = lookups();

            confirm(order.getId(), "attempt-1");

            assertThat(lookups()).isEqualTo(lookupsBefore);
        }
    }

    @Nested
    @DisplayName("SETTLE-007, 9장: PG 가 받은 뒤 우리 쪽 반영이 실패해도 다시 보내지 않고, 조회로 한 번 끝난다")
    class InternalSaveFailure {

        @DisplayName("생성 응답을 반영하는 TX B 가 잠금 대기로 실패하면 오류 없이 REQUESTED 를 돌려주고, 잠금이 풀린 뒤 reconcile 이 한 번 확정한다")
        @Test
        void confirmKeepsRequestedThenReconcileSettles() throws Exception {
            Order order = place(2);
            PgFixture.HeldResponse held = PG.holdNextCreateResponse();
            CompletableFuture<Payment> confirming = CompletableFuture.supplyAsync(() -> confirm(order.getId(), "attempt-1"));
            held.awaitStored();

            Payment returned;
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                lockOrder(holder, order.getId());
                held.release();
                returned = confirming.get(15, TimeUnit.SECONDS);
                holder.rollback();
            }
            String key = PG.transactionsOf(ProviderOrderId.of(order.getId()).value()).stream()
                .map(PgFixture.Transaction::transactionKey).max(String::compareTo).orElseThrow();
            PG.changeStatus(key, PgFixture.Status.SUCCESS);

            Payment reconciled = paymentFacade.reconcile(USER, order.getId(), NOW).payment();

            assertThat(returned.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
            assertThat(reconciled.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(reconciled.getTransactionKey().value()).isEqualTo(key);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
            assertThat(creates()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("PAY-005, 6.7: 발송을 기록한 뒤 서버가 다시 떠도 create 를 다시 보내지 않고, 기존 거래를 조회로 찾는다")
    class Restart {

        private ConfigurableApplicationContext restartedServer() {
            return new SpringApplicationBuilder(CommerceApiApplication.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run("--spring.jpa.hibernate.ddl-auto=none", "--pg.base-url=" + PG.baseUrl());
        }

        private Payment requestedOnly(Order order) {
            return paymentTransactions.begin(
                USER, order.getId(), PaymentAttemptKey.of("attempt-1"), CardType.TEST, NOW).payment();
        }

        @DisplayName("POST 직후 종료: 다시 뜬 서버의 reconcile 이 주문 조회로 그 거래를 찾아 한 번 확정한다. create 는 1 회 그대로다")
        @Test
        void sentBeforeCrash() {
            Order order = place(2);
            Payment requested = requestedOnly(order);
            paymentGateway.create(new PaymentCreation(
                requested.getProviderOrderId(), CardType.TEST, CARD, requested.getAmount()));
            String key = PG.transactionsOf(requested.getProviderOrderId().value()).getFirst().transactionKey();
            PG.changeStatus(key, PgFixture.Status.SUCCESS);

            Payment settled;
            int createsAfterRestart;
            try (ConfigurableApplicationContext restarted = restartedServer()) {
                createsAfterRestart = creates();
                settled = restarted.getBean(PaymentFacade.class).reconcile(USER, order.getId(), NOW).payment();
            }

            assertThat(createsAfterRestart).as("다시 떠도 저절로 보내지 않는다").isEqualTo(1);
            assertThat(creates()).isEqualTo(1);
            assertThat(settled.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(settled.getTransactionKey().value()).isEqualTo(key);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }

        @DisplayName("POST 전에 종료: 조회 0 건이면 미완료 그대로 두고 다시 보내지 않는다. 묶인 재고도 그대로다 (9장 첫 줄)")
        @Test
        void crashedBeforeSending() {
            Order order = place(2);
            requestedOnly(order);

            Payment checked;
            try (ConfigurableApplicationContext restarted = restartedServer()) {
                checked = restarted.getBean(PaymentFacade.class).reconcile(USER, order.getId(), NOW).payment();
            }

            assertThat(creates()).isZero();
            assertThat(checked.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }
    }

    @Nested
    @DisplayName("RESIL-007: PG 생성 경로는 동시에 20 개까지만 진행하고, 자리가 없으면 아무것도 하지 않고 거절한다")
    class Bulkhead {

        @DisplayName("PG 가 응답을 붙잡은 동안 confirm 21 건이 오면 20 건만 PG 에 가고 1 건은 PAYMENT_BUSY 다. 거절된 주문은 재고 · 시도 · create 모두 0")
        @Test
        void rejectsTheTwentyFirst() throws Exception {
            productFacade.adjustStock(coat, Quantity.of(100));
            List<Order> orders = new ArrayList<>();
            for (int i = 0; i < 21; i++) {
                orders.add(place(1));
            }
            PgFixture.CreateGate gate = PG.holdAllCreates();
            ExecutorService executor = Executors.newFixedThreadPool(21);
            List<Future<Object>> calls = new ArrayList<>();
            List<Object> results = new ArrayList<>();
            try {
                for (Order order : orders) {
                    calls.add(executor.submit(() -> {
                        try {
                            return confirm(order.getId(), "attempt-" + order.getId());
                        } catch (CoreException e) {
                            return e;
                        }
                    }));
                }
                assertThat(eventually(() -> gate.arrived() == 20)).as("20 건이 PG 에 닿는다").isTrue();
                assertThat(eventually(() -> calls.stream().filter(Future::isDone).count() == 1))
                    .as("나머지 1 건은 PG 를 기다리지 않고 바로 끝난다").isTrue();
                gate.release();
                for (Future<Object> call : calls) {
                    results.add(call.get(15, TimeUnit.SECONDS));
                }
            } finally {
                gate.release();
                executor.shutdownNow();
            }

            List<CoreException> rejected = results.stream()
                .filter(CoreException.class::isInstance).map(CoreException.class::cast).toList();
            assertThat(results).filteredOn(Payment.class::isInstance).hasSize(20);
            assertThat(rejected).singleElement()
                .satisfies(e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.PAYMENT_BUSY));
            assertThat(creates()).isEqualTo(20);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(80));
            assertThat(orders.stream().filter(order -> paymentRepository.findByOrderId(order.getId()).isEmpty()))
                .singleElement()
                .satisfies(order -> assertThat(orderFacade.get(USER, order.getId()).getStatus())
                    .isEqualTo(OrderStatus.DRAFT));
        }

        @DisplayName("끝난 confirm 은 자리를 돌려준다. 거절로 끝나도 돌려준다 — 20 을 넘게 차례로 불러도 PAYMENT_BUSY 가 나지 않는다")
        @Test
        void returnsPermits() {
            productFacade.adjustStock(coat, Quantity.of(100));
            Long started = place(1).getId();
            confirm(started, "attempt-started");
            for (int i = 0; i < 25; i++) {
                Long orderId = place(1).getId();
                confirm(orderId, "attempt-" + orderId);
            }
            for (int i = 0; i < 25; i++) {
                String newKey = "attempt-again-" + i;
                assertThatThrownBy(() -> confirm(started, newKey))
                    .isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("error", DomainError.ORDER_NOT_DRAFT);
            }
            Long next = place(1).getId();

            assertThat(confirm(next, "attempt-next").getStatus()).isEqualTo(PaymentStatus.PENDING);
        }
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    @Nested
    @DisplayName("RESIL-005, RESIL-006: sweeper 는 사람 대신 미완료 결제를 조회해 끝내고, 이 경로의 조회만 재시도한다")
    class Sweeper {

        private static final Instant LATER = NOW.plusSeconds(11);

        private String keyOf(Order order) {
            return PG.transactionsOf(ProviderOrderId.of(order.getId()).value()).stream()
                .map(PgFixture.Transaction::transactionKey).max(String::compareTo).orElseThrow();
        }

        @DisplayName("PG-011, SETTLE-012: 생성 응답이 끊겨 UNKNOWN 인 결제도 사람 없이 주문 조회로 끝난다. create 는 다시 보내지 않는다")
        @Test
        void settlesUnknownWithoutHuman() {
            Order order = place(2);
            PG.dropNextCreateResponse();
            confirm(order.getId(), "attempt-1");
            PG.changeStatus(keyOf(order), PgFixture.Status.SUCCESS);

            int checked = paymentSweeper.sweepOnce(LATER);

            assertThat(checked).isEqualTo(1);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(creates()).isEqualTo(1);
        }

        @DisplayName("요청한 지 10 초가 안 된 결제는 confirm 이 아직 처리 중일 수 있어 집지 않는다")
        @Test
        void skipsRecentRequests() {
            Order order = place(2);
            confirm(order.getId(), "attempt-1");
            long before = lookups();

            int checked = paymentSweeper.sweepOnce(NOW.plusSeconds(5));

            assertThat(checked).isZero();
            assertThat(lookups()).isEqualTo(before);
        }

        @DisplayName("RESIL-008: 2 초 안에 누가 확인한 결제는 집지 않는다")
        @Test
        void skipsRecentlyChecked() {
            Order order = place(2);
            confirm(order.getId(), "attempt-1");
            paymentFacade.reconcile(USER, order.getId(), NOW.plusSeconds(10));
            long before = lookups();

            int checked = paymentSweeper.sweepOnce(LATER);

            assertThat(checked).isZero();
            assertThat(lookups()).isEqualTo(before);
        }

        @DisplayName("PAY-018: 끝난 결제는 집지 않는다. CONFLICT 와 함께 상태 조건(REQUESTED · PENDING · UNKNOWN)으로 거른다")
        @Test
        void skipsSettled() {
            Order order = place(2);
            PG.rejectNextCreate();
            confirm(order.getId(), "attempt-1");

            assertThat(paymentSweeper.sweepOnce(LATER)).isZero();
        }

        @DisplayName("RESIL-012: 확인한 적 없는 결제를 먼저 집는다. 끝나지 않는 오래된 결제가 매 주기 앞자리를 차지해 새 결제를 밀어내지 않게")
        @Test
        void picksNeverCheckedBeforeOlderChecked() {
            Order old = place(1);
            confirm(old.getId(), "attempt-old");
            paymentSweeper.sweepOnce(LATER);
            Order fresh = place(1);
            paymentFacade.confirm(new PaymentConfirmCommand(
                USER, fresh.getId(), PaymentAttemptKey.of("attempt-fresh"), CardType.TEST, CARD), NOW.plusSeconds(1));
            Instant now = LATER.plusSeconds(3);

            List<Payment> picked = paymentTransactions.pickDueForCheck(now.minusSeconds(10), now.minusSeconds(2), 1, now);

            assertThat(picked).extracting(Payment::getOrderId).containsExactly(fresh.getId());
        }

        @DisplayName("RESIL-012: 확인한 적 있는 결제끼리는 가장 오래전에 확인한 결제를 먼저 집어, 미확정 결제를 돌아가며 확인한다")
        @Test
        void picksLeastRecentlyCheckedFirst() {
            Order first = place(1);
            confirm(first.getId(), "attempt-first");
            Order second = place(1);
            confirm(second.getId(), "attempt-second");
            paymentFacade.reconcile(USER, second.getId(), LATER);
            paymentFacade.reconcile(USER, first.getId(), LATER.plusSeconds(1));
            Instant now = LATER.plusSeconds(5);

            List<Payment> picked = paymentTransactions.pickDueForCheck(now.minusSeconds(10), now.minusSeconds(2), 1, now);

            assertThat(picked).extracting(Payment::getOrderId).containsExactly(second.getId());
        }

        @DisplayName("5xx 는 100 ms, 200 ms 뒤 다시 조회해 최대 3 회 부른다. 세 번째에 성공하면 끝난다")
        @Test
        void retriesServerErrors() {
            Order order = place(2);
            confirm(order.getId(), "attempt-1");
            PG.changeStatus(keyOf(order), PgFixture.Status.SUCCESS);
            PG.failNextLookups(2, 500);
            long before = lookups();

            paymentSweeper.sweepOnce(LATER);

            assertThat(lookups() - before).isEqualTo(3);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @DisplayName("3 회 모두 실패하면 그대로 두고 다음 주기에 다시 본다")
        @Test
        void givesUpAfterThreeAttempts() {
            Order order = place(2);
            confirm(order.getId(), "attempt-1");
            PG.failNextLookups(5, 500);
            long before = lookups();

            paymentSweeper.sweepOnce(LATER);

            assertThat(lookups() - before).isEqualTo(3);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.PENDING);
        }

        @DisplayName("429 는 PG 가 멈추라는 신호라 다시 부르지 않는다. 조회 1 회")
        @Test
        void doesNotRetryTooManyRequests() {
            Order order = place(2);
            confirm(order.getId(), "attempt-1");
            PG.failNextLookups(5, 429);
            long before = lookups();

            paymentSweeper.sweepOnce(LATER);

            assertThat(lookups() - before).isEqualTo(1);
        }

        @DisplayName("sweeper 둘이 동시에 돌아도 SKIP LOCKED 와 확인 시각으로 같은 결제를 겹쳐 조회하지 않는다")
        @Test
        void concurrentSweepsSplitTheWork() throws Exception {
            productFacade.adjustStock(coat, Quantity.of(100));
            for (int i = 0; i < 6; i++) {
                Long orderId = place(1).getId();
                confirm(orderId, "attempt-" + orderId);
            }
            long before = lookups();

            CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> paymentSweeper.sweepOnce(LATER));
            CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> paymentSweeper.sweepOnce(LATER));

            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(6);
            assertThat(lookups() - before).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("9.1: 결제 지표는 실제로 일어난 일과 같은 수를 센다")
    class Metrics {

        private double count(String name, String... tags) {
            Counter counter = meterRegistry.find(name).tags(tags).counter();
            return counter == null ? 0 : counter.count();
        }

        private double gauge(String name, String status) {
            return meterRegistry.get(name).tag("status", status).gauge().value();
        }

        @DisplayName("9.1: 미완료 결제를 상태별로 세고, 가장 오래된 요청의 나이를 초로 낸다. 끝난 결제는 세지 않는다")
        @Test
        void gaugesUnresolvedPayments() {
            productFacade.adjustStock(coat, Quantity.of(100));
            confirm(place(1).getId(), "attempt-pending");
            Order unknown = place(1);
            PG.dropNextCreateResponse();
            paymentFacade.confirm(new PaymentConfirmCommand(
                USER, unknown.getId(), PaymentAttemptKey.of("attempt-unknown"), CardType.TEST, CARD), NOW.plusSeconds(30));
            PG.rejectNextCreate();
            confirm(place(1).getId(), "attempt-failed");

            unresolvedMetrics.refresh(NOW.plusSeconds(90));

            assertThat(gauge("payment.unresolved", "pending")).isEqualTo(1);
            assertThat(gauge("payment.unresolved.oldest", "pending")).isEqualTo(90);
            assertThat(gauge("payment.unresolved", "unknown")).isEqualTo(1);
            assertThat(gauge("payment.unresolved.oldest", "unknown")).isEqualTo(60);
            assertThat(gauge("payment.unresolved", "conflict")).isZero();
        }

        @DisplayName("접수는 payment.create{result=accepted} 와 payment.observe{to=pending} 를 하나씩 센다")
        @Test
        void countsAcceptedCreate() {
            double created = count("payment.create", "result", "accepted");
            double observed = count("payment.observe", "to", "pending");

            confirm(place(2).getId(), "attempt-1");

            assertThat(count("payment.create", "result", "accepted") - created).isEqualTo(1);
            assertThat(count("payment.observe", "to", "pending") - observed).isEqualTo(1);
        }

        @DisplayName("같은 키의 재전송은 create 를 세지 않는다. fixture 의 create 수와 같다")
        @Test
        void createCountMatchesFixture() {
            double created = count("payment.create", "result", "accepted");
            Order order = place(2);

            confirm(order.getId(), "attempt-1");
            confirm(order.getId(), "attempt-1");

            assertThat(count("payment.create", "result", "accepted") - created).isEqualTo(creates());
        }

        @DisplayName("sweeper 의 조회는 재시도가 몇 번이든 논리적 조회 한 번으로 센다")
        @Test
        void countsSweeperLookupOnce() {
            Order order = place(2);
            confirm(order.getId(), "attempt-1");
            PG.failNextLookups(2, 500);
            double looked = count("payment.lookup", "source", "sweeper", "result", "found");

            paymentSweeper.sweepOnce(NOW.plusSeconds(11));

            assertThat(count("payment.lookup", "source", "sweeper", "result", "found") - looked).isEqualTo(1);
        }

        @DisplayName("이미 같은 결과를 본 반영은 payment.observe{to=noop} 로 센다")
        @Test
        void countsNoop() {
            Order order = place(2);
            confirm(order.getId(), "attempt-1");
            double noop = count("payment.observe", "to", "noop");

            paymentFacade.reconcile(USER, order.getId(), NOW.plusSeconds(10));

            assertThat(count("payment.observe", "to", "noop") - noop).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("RSV-001, PAY-003: TX A 가 거절하면 PG 를 부르지 않는다")
    class NotSent {

        @DisplayName("재고가 모자라면 create 0 이다")
        @Test
        void insufficientStock() {
            Order order = place(11);

            assertThatThrownBy(() -> confirm(order.getId(), "attempt-1"))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_STOCK);
            assertThat(creates()).isZero();
        }

        @DisplayName("시도가 있는 주문에 새 키가 오면 create 는 처음 한 번뿐이다")
        @Test
        void newKeyOnStartedOrder() {
            Order order = place(2);
            confirm(order.getId(), "attempt-1");

            assertThatThrownBy(() -> confirm(order.getId(), "attempt-2"))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.ORDER_NOT_DRAFT);
            assertThat(creates()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("PAY-004, ORDER-018: 동시에 와도 PG 생성은 한 번이다")
    class Concurrent {

        @DisplayName("같은 키 confirm 10 건이 동시에 와도 create 1, 시도 1, 재고 차감 1 이고 모두 같은 시도를 받는다")
        @Test
        void sameKey() throws InterruptedException {
            Order order = place(2);

            List<Object> results = runConcurrently(10, i -> confirm(order.getId(), "attempt-1"));

            assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(Payment.class));
            assertThat(results).extracting(result -> ((Payment) result).getId()).containsOnly(
                paymentRepository.findByOrderId(order.getId()).orElseThrow().getId());
            assertThat(creates()).isEqualTo(1);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.PENDING);
        }

        @DisplayName("같은 주문에 다른 키 10 건이 동시에 오면 하나만 보내고 나머지는 ORDER_NOT_DRAFT 다")
        @Test
        void differentKeys() throws InterruptedException {
            Order order = place(2);

            List<Object> results = runConcurrently(10, i -> confirm(order.getId(), "attempt-" + i));

            assertThat(results).filteredOn(result -> result instanceof Payment).hasSize(1);
            assertThat(results).filteredOn(result -> result instanceof DomainException exception
                && exception.error() == DomainError.ORDER_NOT_DRAFT).hasSize(9);
            assertThat(creates()).isEqualTo(1);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }
    }

    private static List<Object> runConcurrently(int threads, IntFunction<Object> call) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Object> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            int index = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    results.add(call.apply(index));
                } catch (Throwable t) {
                    results.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).as("모든 worker 가 준비된다").isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("모든 confirm 이 제한 시간 안에 끝난다").isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("worker 가 정리된다").isTrue();
        return List.copyOf(results);
    }
}
