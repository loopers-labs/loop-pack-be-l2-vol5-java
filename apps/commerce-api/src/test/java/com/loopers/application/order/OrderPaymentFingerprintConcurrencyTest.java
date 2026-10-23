package com.loopers.application.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentRequestConflictException;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.payment.HttpTestPgClient;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;

/** HMAC request identity inside real local transactions; this does not implement customer HTTP responses. */
@SpringBootTest
@Timeout(20)
class OrderPaymentFingerprintConcurrencyTest {
    private static final String CARD = "0000-0000-0000-0000";
    private static final String OTHER_CARD = "1111-1111-1111-1111";

    @Autowired private OrderPaymentStockService stocks;
    @Autowired private OrderService orders;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private DataSource dataSource;
    @MockitoSpyBean private PaymentAttemptRepository attempts;
    @MockitoSpyBean private PaymentAttemptJpaRepository jpaAttempts;
    @MockitoSpyBean private ProductListRevisionRepository revisions;
    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    private LocalTestPg pg;
    private HttpTestPgClient client;
    private OrderPaymentCoordinator coordinator;
    private ExecutorService workers;

    @BeforeEach
    void setUp() throws Exception {
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=10000 WHERE id IN (1,2)");
        assertThat(AopUtils.isAopProxy(stocks)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        pg = new LocalTestPg();
        client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), new ObjectMapper(), PgHttpTimeouts.defaults());
        coordinator = new OrderPaymentCoordinator(stocks, client);
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (pg != null) {
            pg.releaseResponses();
        }
        boolean terminated = workers == null;
        try {
            if (workers != null) {
                workers.shutdownNow();
                terminated = workers.awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            if (client != null) {
                client.close();
            }
            if (pg != null) {
                pg.close();
            }
            PaymentAttemptRepository attemptTarget = AopTestUtils.getUltimateTargetObject(attempts);
            ProductListRevisionRepository revisionTarget = AopTestUtils.getUltimateTargetObject(revisions);
            reset(attemptTarget, revisionTarget, jpaAttempts);
            if (terminated) {
                cleanup.truncateAllTables();
            }
        }
        assertThat(terminated).as("독립 worker가 모두 종료된 뒤에만 DB 정리").isTrue();
    }

    @Test
    void sameRequestReturnsCommittedSnapshotWhileFirstHttpIsStillWaiting() throws Exception {
        Fixture fixture = draft("alice", 1, UUID.randomUUID());
        long revision = revisions.current();
        pg.nextCreateMode(LocalTestPg.CreateMode.HOLD_RESPONSE_AFTER_CREATE);
        var first = workers.submit(() -> start(fixture, CARD));
        assertThat(pg.awaitCreate(Duration.ofSeconds(5))).isTrue();
        assertThat(first.isDone()).as("첫 요청은 실제 PG HTTP 응답을 기다린다").isFalse();
        byte[] storedDigest = attempts.findByOrderId(fixture.orderId()).orElseThrow()
            .requireRequestFingerprint().digest();

        var repeated = workers.submit(() -> start(fixture, CARD)).get(5, TimeUnit.SECONDS);

        assertThat(repeated.failure()).isNull();
        assertThat(repeated.result().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(repeated.result().paymentStatus()).isEqualTo(PaymentAttemptStatus.NOT_OBSERVED);
        assertThat(first.isDone()).as("같은 요청이 선행 HTTP 종료까지 기다리지 않는다").isFalse();
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
        pg.releaseResponses();
        var original = first.get(5, TimeUnit.SECONDS);

        assertThat(original.failure()).isNull();
        assertThat(original.result().paymentStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertPrepared(fixture, CARD);
        assertThat(MessageDigest.isEqual(storedDigest,
            attempts.findByOrderId(fixture.orderId()).orElseThrow().requireRequestFingerprint().digest())).isTrue();
        assertSingleExternalEffect(revision);
    }

    @Test
    void changedCardConflictsWhileFirstRequestIsWaitingWithoutChangingItsFingerprint() throws Exception {
        Fixture fixture = draft("alice", 1, UUID.randomUUID());
        long revision = revisions.current();
        pg.nextCreateMode(LocalTestPg.CreateMode.HOLD_RESPONSE_AFTER_CREATE);
        var first = workers.submit(() -> start(fixture, CARD));
        assertThat(pg.awaitCreate(Duration.ofSeconds(5))).isTrue();
        var before = storedState(jdbc);
        byte[] storedDigest = attempts.findByOrderId(fixture.orderId()).orElseThrow()
            .requireRequestFingerprint().digest();

        var changed = workers.submit(() -> start(fixture, OTHER_CARD)).get(5, TimeUnit.SECONDS);

        assertThat(changed.result()).isNull();
        assertThat(changed.failure()).isInstanceOf(PaymentRequestConflictException.class);
        assertThat(first.isDone()).isFalse();
        assertThat(storedState(jdbc)).isEqualTo(before);
        assertThat(MessageDigest.isEqual(storedDigest,
            attempts.findByOrderId(fixture.orderId()).orElseThrow().requireRequestFingerprint().digest())).isTrue();
        pg.releaseResponses();
        var original = first.get(5, TimeUnit.SECONDS);

        assertThat(original.failure()).isNull();
        assertThat(original.result().paymentStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertPrepared(fixture, CARD);
        assertSingleExternalEffect(revision);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoDraftRequestsStartTogetherAndOnlyTheMatchingRequestCanReuseTheWinner(boolean changedCard) throws Exception {
        Fixture fixture = draft("alice", 1, UUID.randomUUID());
        long revision = revisions.current();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        String secondCard = changedCard ? OTHER_CARD : CARD;
        try {
            var first = workers.submit(() -> startTogether(fixture, CARD, ready, go));
            var second = workers.submit(() -> startTogether(fixture, secondCard, ready, go));
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("두 worker 모두 DRAFT 시작 신호를 기다림").isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId()))
                .isEqualTo("DRAFT");
            assertThat(attempts.findByOrderId(fixture.orderId())).isEmpty();
            go.countDown();
            Outcome firstResult = first.get(10, TimeUnit.SECONDS);
            Outcome secondResult = second.get(10, TimeUnit.SECONDS);
            List<Outcome> outcomes = List.of(firstResult, secondResult);
            var successes = outcomes.stream().filter(outcome -> outcome.failure() == null).toList();
            var conflicts = outcomes.stream()
                .filter(outcome -> outcome.failure() instanceof PaymentRequestConflictException).toList();

            assertThat(successes).hasSize(changedCard ? 1 : 2);
            assertThat(conflicts).hasSize(changedCard ? 1 : 0);
            assertThat(successes.size() + conflicts.size()).as("기술 오류를 정상 충돌로 세지 않음").isEqualTo(2);
            assertThat(successes).allSatisfy(outcome -> {
                assertThat(outcome.result().orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
                assertThat(outcome.result().paymentStatus())
                    .isIn(PaymentAttemptStatus.NOT_OBSERVED, PaymentAttemptStatus.PENDING);
            });
            assertThat(conflicts).allSatisfy(outcome -> assertThat(outcome.result()).isNull());
            String winningCard = firstResult.failure() == null ? CARD : secondCard;
            assertPrepared(fixture, winningCard);
            if (changedCard) {
                String rejectedCard = firstResult.failure() == null ? secondCard : CARD;
                assertThat(fingerprinter.matches(new PaymentFingerprintInput(fixture.userId(), fixture.orderId(), 200,
                    "TEST", rejectedCard), attempts.findByOrderId(fixture.orderId()).orElseThrow().requireRequestFingerprint()))
                    .as("실제 승자의 지문만 남고 다른 요청은 덮어쓰지 않음").isFalse();
            }
            assertSingleExternalEffect(revision);
        } finally {
            go.countDown();
        }
    }

    @Test
    void sameGlobalKeyForDifferentOrdersReachesDatabaseUniqueConstraintAndRollsBackTheLoser() throws Exception {
        UUID sharedKey = UUID.randomUUID();
        Fixture alice = draft("alice", 1, sharedKey);
        Fixture bob = draft("bob", 2, sharedKey);
        var before = storedState(jdbc);
        long revision = revisions.current();
        CountDownLatch bothReadEmpty = new CountDownLatch(2);
        AtomicInteger initialReads = new AtomicInteger();
        AtomicInteger duplicateSqlFailures = new AtomicInteger();
        Set<Long> connections = ConcurrentHashMap.newKeySet();
        PaymentAttemptRepository target = AopTestUtils.getUltimateTargetObject(attempts);
        doAnswer(invocation -> {
            Object found = invocation.callRealMethod();
            if (initialReads.incrementAndGet() <= 2) {
                assertThat((Optional<?>) found).isEmpty();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                bothReadEmpty.countDown();
                if (!bothReadEmpty.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Both transactions must observe the key as absent");
                }
            }
            return found;
        }).when(target).findByAttemptKey(sharedKey);
        // Observe before the domain adapter removes the storage exception cause from its public conflict.
        var realJpaAnswer = mockingDetails(jpaAttempts).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            try {
                return realJpaAnswer.answer(invocation);
            } catch (RuntimeException failure) {
                if (isDuplicateKey(failure)) {
                    duplicateSqlFailures.incrementAndGet();
                }
                throw failure;
            }
        }).when(jpaAttempts).save(any(PaymentAttempt.class));

        var first = workers.submit(() -> start(alice, CARD));
        var second = workers.submit(() -> start(bob, CARD));
        List<Outcome> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

        assertThat(connections).as("별도 MySQL 연결의 실제 동시 TX").hasSize(2);
        assertThat(duplicateSqlFailures).as("선조회만으로 막은 것이 아니라 실제 UNIQUE 경합 발생").hasValue(1);
        var successes = outcomes.stream().filter(outcome -> outcome.failure() == null).toList();
        var conflicts = outcomes.stream().filter(outcome -> outcome.failure() instanceof PaymentRequestConflictException).toList();
        assertThat(successes).hasSize(1);
        assertThat(conflicts).hasSize(1);
        assertThat(successes.getFirst().result().paymentStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        Fixture winner = successes.getFirst().fixture();
        Fixture loser = conflicts.getFirst().fixture();
        assertThat(conflicts.getFirst().result()).isNull();
        assertPrepared(winner, CARD);
        assertThat(attempts.findByOrderId(loser.orderId())).isEmpty();
        var after = storedState(jdbc);
        assertThat(row(after.get("orders"), loser.orderId())).isEqualTo(row(before.get("orders"), loser.orderId()));
        assertThat(row(after.get("products"), loser.productId())).isEqualTo(row(before.get("products"), loser.productId()));
        assertThat(items(after, loser.orderId())).isEqualTo(items(before, loser.orderId()));
        assertThat(after.get("users")).isEqualTo(before.get("users"));
        assertSingleExternalEffect(revision);
    }

    @Test
    void actualFingerprintAndReservationSqlRollBackTogetherBeforeAnyPgRequest() throws Exception {
        Fixture fixture = draft("alice", 1, UUID.randomUUID());
        var before = storedState(jdbc);
        long revision = revisions.current();
        AtomicBoolean observedWrite = new AtomicBoolean();
        InjectedFailure injected = new InjectedFailure();
        ProductListRevisionRepository target = AopTestUtils.getUltimateTargetObject(revisions);
        try (var independentConnection = dataSource.getConnection()) {
            var independent = new JdbcTemplate(new SingleConnectionDataSource(independentConnection, true));
            long independentId = independent.queryForObject("SELECT CONNECTION_ID()", Long.class);
            doAnswer(invocation -> {
                invocation.callRealMethod();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                entityManager.flush();
                assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isNotEqualTo(independentId);
                assertThat(jdbc.queryForObject("SELECT OCTET_LENGTH(request_fingerprint) FROM payment_attempt WHERE order_id=?",
                    Integer.class, fixture.orderId())).isEqualTo(32);
                assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class,
                    fixture.orderId())).isEqualTo("NOT_OBSERVED");
                assertThat(attempts.findByOrderId(fixture.orderId()).orElseThrow().getSendStartedAt()).isNotNull();
                assertThat(jdbc.queryForObject("SELECT reservation_status FROM order_item WHERE order_id=?", String.class,
                    fixture.orderId())).isEqualTo("HELD");
                assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class,
                    fixture.productId())).isEqualTo(3);
                assertThat(revisions.current()).isEqualTo(revision + 1);
                observedWrite.set(true);
                throw injected;
            }).when(target).advance();

            assertThatThrownBy(() -> coordinator.startOrReuse(fixture.requester(), fixture.orderId(), fixture.key(),
                fixture.providerId(), "TEST", CARD)).isSameAs(injected);

            assertThat(observedWrite).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(storedState(independent)).as("실제 저장 뒤 rollback을 별도 연결로 재조회").isEqualTo(before);
            assertThat(pg.createCalls()).isZero();
            assertThat(pg.lookupCalls()).isZero();
            assertThat(pg.transactionCount()).isZero();
        }
    }

    private Fixture draft(String requester, long userId, UUID key) {
        Product product = products.save(new Product(brands.save(new Brand("지문 경합 브랜드")), "상품", 100, 5));
        long orderId = orders.create(requester, List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        return new Fixture(requester, userId, product.getId(), orderId, key, "FINGERPRINT-ORDER-" + orderId);
    }

    private Outcome start(Fixture fixture, String card) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try {
            return new Outcome(fixture, coordinator.startOrReuse(fixture.requester(), fixture.orderId(), fixture.key(),
                fixture.providerId(), "TEST", card), null);
        } catch (RuntimeException failure) {
            return new Outcome(fixture, null, failure);
        } finally {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    private Outcome startTogether(Fixture fixture, String card, CountDownLatch ready, CountDownLatch go)
        throws InterruptedException {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out before concurrent payment start");
        }
        return start(fixture, card);
    }

    private void assertPrepared(Fixture fixture, String card) {
        PaymentAttempt attempt = attempts.findByOrderId(fixture.orderId()).orElseThrow();
        assertThat(attempt.getAttemptKey()).isEqualTo(fixture.key());
        assertThat(attempt.getAmount()).isEqualTo(200);
        assertThat(attempt.getSendStartedAt()).isNotNull();
        var fingerprint = attempt.requireRequestFingerprint();
        assertThat(fingerprint.fingerprintVersion()).isEqualTo(1);
        assertThat(fingerprint.keyVersion()).isPositive();
        assertThat(fingerprinter.matches(new PaymentFingerprintInput(fixture.userId(), fixture.orderId(), 200,
            "TEST", card), fingerprint)).as("저장 지문이 실제 승자의 검증된 입력과 일치").isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId()))
            .isEqualTo("PAYMENT_PENDING");
        assertThat(jdbc.queryForObject("SELECT payment_method FROM `order` WHERE id=?", String.class, fixture.orderId()))
            .isEqualTo("PG");
        assertThat(jdbc.queryForList("SELECT reservation_status FROM order_item WHERE order_id=?", String.class,
            fixture.orderId())).containsExactly("HELD");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.productId()))
            .isEqualTo(3);
    }

    private void assertSingleExternalEffect(long previousRevision) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Integer.class)).isEqualTo(1);
        assertThat(pg.createCalls()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
        assertThat(pg.lookupCalls()).isZero();
        assertThat(revisions.current()).isEqualTo(previousRevision + 1);
        assertThat(jdbc.queryForList("SELECT point_balance FROM user WHERE id IN (1,2) ORDER BY id", Long.class))
            .containsExactly(10000L, 10000L);
    }

    private static boolean isDuplicateKey(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, List<Map<String, Object>>> storedState(JdbcTemplate connection) {
        return Map.of("orders", connection.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", connection.queryForList("SELECT * FROM order_item ORDER BY id"),
            "attempts", connection.queryForList("SELECT id,order_id,HEX(attempt_key),provider_account,provider_order_id,"
                + "amount,status,send_started_at,transaction_key,OCTET_LENGTH(request_fingerprint),fingerprint_version,key_version "
                + "FROM payment_attempt ORDER BY id"),
            "products", connection.queryForList("SELECT * FROM product ORDER BY id"),
            "users", connection.queryForList("SELECT * FROM user ORDER BY id"),
            "revisions", connection.queryForList("SELECT * FROM product_list_revision ORDER BY id"));
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, long id) {
        return rows.stream().filter(value -> ((Number) value.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    private static List<Map<String, Object>> items(Map<String, List<Map<String, Object>>> state, long orderId) {
        return state.get("items").stream().filter(value -> ((Number) value.get("order_id")).longValue() == orderId).toList();
    }

    private record Fixture(String requester, long userId, long productId, long orderId, UUID key, String providerId) { }

    private record Outcome(Fixture fixture, OrderPaymentStockService.AppliedPayment result, RuntimeException failure) { }

    private static final class InjectedFailure extends RuntimeException {
        private InjectedFailure() {
            super("test-only failure after fingerprint TX A SQL");
        }
    }
}
