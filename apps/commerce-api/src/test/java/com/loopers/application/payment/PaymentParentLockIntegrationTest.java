package com.loopers.application.payment;

import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.SqlCapture;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import com.loopers.testsupport.RecordingOrderEventPublisher;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 설계 4.5 「부모 잠금과 일반 조회의 실제 MySQL 검증」. 업무 TX 를 독립 스레드·연결에서 실행하고, 겹침은 seam 장벽과 InnoDB 잠금 대기
 * 관측으로 통제한다. 공유 상품으로 직렬화되지 않게 서로 다른 주문·키·상품을 쓴다. 모든 대기는 10초 안이며 finally 에서 푼다.
 */
@SpringBootTest(properties = SqlCapture.PROPERTY)
@Import(PaymentTxTestConfig.class)
@DisplayName("결제 TX 는 Order 잠금으로 같은 주문만 직렬화하고, 잠금을 기다린 TX 는 앞선 commit 결과를 읽는다.")
class PaymentParentLockIntegrationTest {

    @TestBean(name = "kafkaOrderEventPublisher", methodName = RecordingOrderEventPublisher.FACTORY, enforceOverride = true)
    private OrderEventPublisher orderEventPublisher;

    private static final Long USER = 1L;
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String KEY_C = "123e4567-e89b-12d3-a456-426614174002";

    @Autowired
    private PaymentTransactionService service;
    @Autowired
    private PaymentTxFixture fixture;
    @Autowired
    private ControllableProbe probe;
    @Autowired
    private ConstraintViolationClassifier classifier;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ExecutorService workers;
    private final List<CountDownLatch> releases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        try {
            releases.forEach(CountDownLatch::countDown);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS)).as("TX 스레드 종료").isTrue();
        } finally {
            probe.clear();
            databaseCleanUp.truncateAllTables();
        }
    }

    @DisplayName("업무 TX 의 첫 업무 SQL 은 Order PK 잠금이고 Payment·PaymentConflict 에는 잠금 조회가 없다. 콜백 후보 식별은 잠그지 않는다.")
    @Test
    void firstStatementLocksOrder() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = fixture.draftOrder(USER, 1L, product);
        Long other = fixture.draftOrder(USER, 1L, product);
        PaymentConfirmCommand command = PaymentTxFixture.command(PaymentTxFixture.KEY_A);

        List<String> prepare = capture(() -> service.prepareConfirmation(USER, orderId, command));
        AtomicReference<BeginConfirmationResult> begun = new AtomicReference<>();
        List<String> begin = capture(() -> begun.set(service.beginConfirmation(USER, orderId, command)));
        PaymentCheckToken createToken = begun.get().token();
        List<String> verify = capture(() -> service.prepareVerification(orderId, USER, null));
        PaymentObservation.TransactionObserved conflicting = tx(ObservationSource.CREATE, orderId, "tx-A", 1L, PgTransactionStatus.PENDING);
        List<String> apply = capture(() -> service.applyObservation(createToken, conflicting));
        List<String> constraint = capture(() -> service.recordConstraintConflict(createToken,
            tx(ObservationSource.CREATE, orderId, "tx-A", 1_000L, PgTransactionStatus.PENDING)));
        service.beginConfirmation(USER, other, PaymentTxFixture.command(PaymentTxFixture.KEY_B));
        List<String> resolve = capture(() -> service.resolveCallbackOrderId(PaymentModel.providerOrderIdOf(orderId)));

        List<List<String>> businessTx = List.of(prepare, begin, verify, apply, constraint);
        assertAll(
            () -> assertThat(businessTx).allSatisfy(statements -> assertThat(lower(statements.get(0)))
                .startsWith("select").contains(" from orders ").endsWith("for update")),
            () -> assertThat(businessTx).allSatisfy(statements -> assertThat(statements)
                .noneMatch(sql -> lower(sql).contains("from payment") && lower(sql).contains("for update"))),
            () -> assertThat(apply).anyMatch(sql -> lower(sql).startsWith("insert into payment_conflict")),
            () -> assertThat(resolve).singleElement().satisfies(sql -> assertThat(lower(sql))
                .contains("from payment").doesNotContain("for update"))
        );
    }

    @DisplayName("서로 다른 주문의 첫 TX A 는 둘 다 결제 조회를 마치고 INSERT 직전에서 만난 뒤 모두 commit 한다.")
    @Test
    void independentFirstCreatesOverlap() throws Exception {
        ProductModel p1 = fixture.product(1_000L, 10L);
        ProductModel p2 = fixture.product(1_000L, 10L);
        Long first = fixture.draftOrder(USER, 1L, p1);
        Long second = fixture.draftOrder(USER, 1L, p2);
        CyclicBarrier beforeInsert = new CyclicBarrier(2);
        probe.on(PaymentTransactionProbe.Point.BEFORE_PAYMENT_INSERT, "beginConfirmation", first, () -> awaitBarrier(beforeInsert));
        probe.on(PaymentTransactionProbe.Point.BEFORE_PAYMENT_INSERT, "beginConfirmation", second, () -> awaitBarrier(beforeInsert));

        Future<BeginConfirmationResult> a = workers.submit(() ->
            service.beginConfirmation(USER, first, PaymentTxFixture.command(PaymentTxFixture.KEY_A)));
        Future<BeginConfirmationResult> b = workers.submit(() ->
            service.beginConfirmation(USER, second, PaymentTxFixture.command(PaymentTxFixture.KEY_B)));

        assertAll(
            () -> assertThat(get(a).status()).isEqualTo(BeginConfirmationResult.Status.SEND),
            () -> assertThat(get(b).status()).isEqualTo(BeginConfirmationResult.Status.SEND),
            () -> assertThat(fixture.paymentCount(first)).isEqualTo(1L),
            () -> assertThat(fixture.paymentCount(second)).isEqualTo(1L),
            () -> assertThat(fixture.stock(p1.getId())).isEqualTo(9L),
            () -> assertThat(fixture.stock(p2.getId())).isEqualTo(9L),
            () -> assertThat(fixture.histories(first, "ORDER_RESERVATION")).isEqualTo(1L)
        );
    }

    @DisplayName("같은 주문의 동시 TX A 는 Order 잠금으로 직렬화되어 하나만 SEND 이고, 기다린 쪽은 STORED 로 결제·선차감·확보 이력을 더 만들지 않는다.")
    @Test
    void sameOrderBeginsSerialize() throws Exception {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = fixture.draftOrder(USER, 2L, product);
        CountDownLatch firstReached = new CountDownLatch(1);
        CountDownLatch firstRelease = release();
        AtomicBoolean firstCaller = new AtomicBoolean(true);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "beginConfirmation", orderId, () -> {
            if (firstCaller.getAndSet(false)) {
                firstReached.countDown();
                awaitLatch(firstRelease);
            }
        });

        Future<BeginConfirmationResult> a = workers.submit(() ->
            service.beginConfirmation(USER, orderId, PaymentTxFixture.command(PaymentTxFixture.KEY_A)));
        awaitLatch(firstReached);
        Future<BeginConfirmationResult> b = workers.submit(() ->
            service.beginConfirmation(USER, orderId, PaymentTxFixture.command(PaymentTxFixture.KEY_A)));
        await().atMost(WAIT).until(fixture::someoneWaitsForOrderLock);
        firstRelease.countDown();

        assertAll(
            () -> assertThat(get(a).status()).isEqualTo(BeginConfirmationResult.Status.SEND),
            () -> assertThat(get(b).status()).isEqualTo(BeginConfirmationResult.Status.STORED),
            () -> assertThat(fixture.paymentCount(orderId)).isEqualTo(1L),
            () -> assertThat(fixture.histories(orderId, "ORDER_RESERVATION")).isEqualTo(1L),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(8L)
        );
    }

    @DisplayName("다른 주문이 같은 시도 키로 동시에 첫 결제를 만들면 하나만 commit 되고 다른 하나는 uk_payment_key 로 전부 rollback 된다.")
    @Test
    void sameAttemptKeyAcrossOrders() throws Exception {
        ProductModel p1 = fixture.product(1_000L, 10L);
        ProductModel p2 = fixture.product(1_000L, 10L);
        Long first = fixture.draftOrder(USER, 1L, p1);
        Long second = fixture.draftOrder(USER, 1L, p2);
        CyclicBarrier beforeInsert = new CyclicBarrier(2);
        probe.on(PaymentTransactionProbe.Point.BEFORE_PAYMENT_INSERT, "beginConfirmation", first, () -> awaitBarrier(beforeInsert));
        probe.on(PaymentTransactionProbe.Point.BEFORE_PAYMENT_INSERT, "beginConfirmation", second, () -> awaitBarrier(beforeInsert));

        Future<BeginConfirmationResult> a = workers.submit(() ->
            service.beginConfirmation(USER, first, PaymentTxFixture.command(PaymentTxFixture.KEY_A)));
        Future<BeginConfirmationResult> b = workers.submit(() ->
            service.beginConfirmation(USER, second, PaymentTxFixture.command(PaymentTxFixture.KEY_A)));
        Throwable aFailure = failureOf(a);
        Throwable bFailure = failureOf(b);
        Throwable loser = aFailure != null ? aFailure : bFailure;
        Long loserOrder = aFailure != null ? first : second;
        ProductModel loserProduct = aFailure != null ? p1 : p2;

        assertAll(
            () -> assertThat(aFailure == null ^ bFailure == null).as("정확히 하나만 실패").isTrue(),
            () -> assertThat(loser).isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThat(classifier.namedUniqueViolation(loser)).hasValue("uk_payment_key"),
            () -> assertThat(fixture.paymentCount(loserOrder)).isZero(),
            () -> assertThat(fixture.stock(loserProduct.getId())).isEqualTo(10L),
            () -> assertThat(fixture.histories(loserOrder, "ORDER_RESERVATION")).isZero(),
            () -> assertThat(fixture.order(loserOrder)).containsEntry("status", "DRAFT")
        );
    }

    @DisplayName("서로 다른 결제의 첫 충돌 요약은 둘 다 기존 요약 조회를 마치고 INSERT 직전에서 만난 뒤 각각 한 행으로 commit 된다.")
    @Test
    void independentFirstConflictsOverlap() throws Exception {
        Long first = begunOrder(PaymentTxFixture.KEY_A);
        Long second = begunOrder(PaymentTxFixture.KEY_B);
        PaymentCheckToken firstToken = service.prepareVerification(first, USER, null).token();
        PaymentCheckToken secondToken = service.prepareVerification(second, USER, null).token();
        CyclicBarrier beforeInsert = new CyclicBarrier(2);
        probe.on(PaymentTransactionProbe.Point.BEFORE_CONFLICT_INSERT, "recordConflictSummary", first, () -> awaitBarrier(beforeInsert));
        probe.on(PaymentTransactionProbe.Point.BEFORE_CONFLICT_INSERT, "recordConflictSummary", second, () -> awaitBarrier(beforeInsert));

        Future<PaymentApplyResult> a = workers.submit(() -> service.applyObservation(firstToken,
            tx(ObservationSource.QUERY, first, "tx-1", 1L, PgTransactionStatus.PENDING)));
        Future<PaymentApplyResult> b = workers.submit(() -> service.applyObservation(secondToken,
            tx(ObservationSource.QUERY, second, "tx-2", 1L, PgTransactionStatus.PENDING)));

        assertAll(
            () -> assertThat(get(a).outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(get(b).outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(first)).hasSize(1),
            () -> assertThat(fixture.conflicts(second)).hasSize(1)
        );
    }

    @DisplayName("같은 결제의 동시 첫 충돌은 뒤 TX 가 Order 잠금을 기다렸다가 앞선 요약을 읽어 교체하며 한 행·500 없음이다.")
    @Test
    void sameParentFirstConflict() throws Exception {
        Long orderId = begunOrder(PaymentTxFixture.KEY_A);
        PaymentCheckToken t1 = service.prepareVerification(orderId, USER, null).token();
        PaymentCheckToken t2 = service.prepareVerification(orderId, USER, null).token();
        CountDownLatch t1Reached = new CountDownLatch(1);
        CountDownLatch t1Release = release();
        AtomicBoolean firstCaller = new AtomicBoolean(true);
        probe.on(PaymentTransactionProbe.Point.BEFORE_CONFLICT_INSERT, "recordConflictSummary", orderId, () -> {
            if (firstCaller.getAndSet(false)) {
                t1Reached.countDown();
                awaitLatch(t1Release);
            }
        });
        CountDownLatch t2AtCommit = new CountDownLatch(1);
        CountDownLatch t2Release = release();
        AtomicBoolean firstCommit = new AtomicBoolean(true);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", orderId, () -> {
            if (!firstCommit.getAndSet(false)) {
                t2AtCommit.countDown();
                awaitLatch(t2Release);
            }
        });

        Future<PaymentApplyResult> a = workers.submit(() -> service.applyObservation(t1,
            tx(ObservationSource.QUERY, orderId, "tx-KB", 1L, PgTransactionStatus.PENDING)));
        awaitLatch(t1Reached);
        Future<PaymentApplyResult> b = workers.submit(() -> service.applyObservation(t2,
            tx(ObservationSource.QUERY, orderId, "tx-other", 7L, PgTransactionStatus.SUCCESS)));
        await().atMost(WAIT).until(fixture::someoneWaitsForOrderLock);
        t1Release.countDown();
        PaymentApplyResult first = get(a);
        try {
            awaitLatch(t2AtCommit);
            assertThat(fixture.conflicts(orderId)).as("뒤 TX commit 직전에 보이는 앞 TX 의 commit 요약").singleElement()
                .satisfies(row -> assertThat(row).containsEntry("code", "AMOUNT_MISMATCH").containsEntry("observed_transaction_key", "tx-KB"));
        } finally {
            t2Release.countDown();
        }

        PaymentApplyResult second = get(b);
        assertAll(
            () -> assertThat(first.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(second.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row -> assertThat(row)
                .as("마지막 commit 내용").containsEntry("observed_transaction_key", "tx-other").containsEntry("observed_amount", 7L))
        );
    }

    @DisplayName("[필수] 실패 반환 SQL 뒤 commit 전에 멈춘 TX 를 기다린 같은 FAILED 는 최신 FAILED 를 읽어 추가 반환·ORDER_RELEASE 가 없다.")
    @Test
    void waitsThenReadsLatestFailure() throws Exception {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = fixture.draftOrder(USER, 2L, product);
        service.beginConfirmation(USER, orderId, PaymentTxFixture.command(PaymentTxFixture.KEY_A));
        PaymentCheckToken t1 = service.prepareVerification(orderId, USER, null).token();
        PaymentCheckToken t2 = service.prepareVerification(orderId, USER, null).token();
        CountDownLatch t1Reached = new CountDownLatch(1);
        CountDownLatch t1Release = release();
        AtomicBoolean firstCaller = new AtomicBoolean(true);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", orderId, () -> {
            if (firstCaller.getAndSet(false)) {
                t1Reached.countDown();
                awaitLatch(t1Release);
            }
        });
        PaymentObservation.TransactionObserved failed = tx(ObservationSource.QUERY, orderId, "tx-A", 2_000L, PgTransactionStatus.FAILED);

        Future<PaymentApplyResult> a = workers.submit(() -> service.applyObservation(t1, failed));
        awaitLatch(t1Reached);
        long releasesWhileT1Holds = fixture.histories(orderId, "ORDER_RELEASE");
        Future<PaymentApplyResult> b = workers.submit(() -> service.applyObservation(t2, failed));
        await().atMost(WAIT).until(fixture::someoneWaitsForOrderLock);
        boolean t2DoneBeforeRelease = b.isDone();
        t1Release.countDown();

        PaymentApplyResult first = get(a);
        PaymentApplyResult second = get(b);
        assertAll(
            () -> assertThat(releasesWhileT1Holds).as("T1 commit 전에는 다른 연결에서 반환이 안 보임").isZero(),
            () -> assertThat(t2DoneBeforeRelease).isFalse(),
            () -> assertThat(first.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(second.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(fixture.histories(orderId, "ORDER_RELEASE")).isEqualTo(1L),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(10L),
            () -> assertThat(fixture.order(orderId)).containsEntry("status", "PAYMENT_FAILED")
        );
    }

    @DisplayName("[필수 대조] SUCCESS commit 을 기다린 반대 FAILED 는 성공을 보존하고 TERMINAL_MISMATCH·반환 0 이다.")
    @Test
    void waitsThenKeepsLatestSuccess() throws Exception {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = fixture.draftOrder(USER, 2L, product);
        service.beginConfirmation(USER, orderId, PaymentTxFixture.command(PaymentTxFixture.KEY_A));
        PaymentCheckToken t1 = service.prepareVerification(orderId, USER, null).token();
        PaymentCheckToken t2 = service.prepareVerification(orderId, USER, null).token();
        CountDownLatch t1Reached = new CountDownLatch(1);
        CountDownLatch t1Release = release();
        AtomicBoolean firstCaller = new AtomicBoolean(true);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", orderId, () -> {
            if (firstCaller.getAndSet(false)) {
                t1Reached.countDown();
                awaitLatch(t1Release);
            }
        });

        Future<PaymentApplyResult> a = workers.submit(() -> service.applyObservation(t1,
            tx(ObservationSource.QUERY, orderId, "tx-A", 2_000L, PgTransactionStatus.SUCCESS)));
        awaitLatch(t1Reached);
        Future<PaymentApplyResult> b = workers.submit(() -> service.applyObservation(t2,
            tx(ObservationSource.QUERY, orderId, "tx-A", 2_000L, PgTransactionStatus.FAILED)));
        await().atMost(WAIT).until(fixture::someoneWaitsForOrderLock);
        t1Release.countDown();

        PaymentApplyResult second = get(b);
        assertAll(
            () -> assertThat(get(a).outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(second.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("code", "TERMINAL_MISMATCH")),
            () -> assertThat(fixture.order(orderId)).containsEntry("status", "CONFIRMED"),
            () -> assertThat(fixture.histories(orderId, "ORDER_RELEASE")).isZero(),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(8L)
        );
    }

    @DisplayName("콜백 후보 식별 뒤 다른 TX 가 SUCCESS 를 commit 하면 새 확인 준비는 종결로 시작하고 PG PENDING 은 TERMINAL_REGRESSION 이다.")
    @Test
    void callbackCandidateThenTerminal() {
        Long orderId = begunOrder(PaymentTxFixture.KEY_A);
        PaymentCheckToken pendingToken = service.prepareVerification(orderId, USER, null).token();
        service.applyObservation(pendingToken, tx(ObservationSource.QUERY, orderId, "tx-A", 1_000L, PgTransactionStatus.PENDING));

        Long resolved = service.resolveCallbackOrderId(PaymentModel.providerOrderIdOf(orderId));
        service.applyObservation(service.prepareVerification(orderId, USER, null).token(),
            tx(ObservationSource.QUERY, orderId, "tx-A", 1_000L, PgTransactionStatus.SUCCESS));
        VerificationTarget callback = service.prepareVerification(resolved, null, "tx-A");
        PaymentApplyResult result = service.applyObservation(callback.token(),
            tx(ObservationSource.QUERY, orderId, "tx-A", 1_000L, PgTransactionStatus.PENDING));

        assertAll(
            () -> assertThat(callback.token().terminalAtStart()).isTrue(),
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(fixture.conflicts(orderId)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("code", "TERMINAL_REGRESSION")),
            () -> assertThat(fixture.order(orderId)).containsEntry("status", "CONFIRMED"),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "SUCCESS")
        );
    }

    private Long begunOrder(String key) {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = fixture.draftOrder(USER, 1L, product);
        service.beginConfirmation(USER, orderId, PaymentTxFixture.command(key));
        return orderId;
    }

    private static PaymentObservation.TransactionObserved tx(ObservationSource source, Long orderId, String key, long amount,
                                                             PgTransactionStatus status) {
        return new PaymentObservation.TransactionObserved(source, key, PaymentModel.providerOrderIdOf(orderId), amount, status);
    }

    private static List<String> capture(Runnable call) {
        SqlCapture.start();
        try {
            call.run();
        } catch (RuntimeException e) {
            SqlCapture.stop();
            throw e;
        }
        return SqlCapture.stop();
    }

    private static String lower(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private CountDownLatch release() {
        CountDownLatch latch = new CountDownLatch(1);
        releases.add(latch);
        return latch;
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("장벽 대기 실패", e);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 기한 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static <T> T get(Future<T> future) throws Exception {
        return future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }
}
