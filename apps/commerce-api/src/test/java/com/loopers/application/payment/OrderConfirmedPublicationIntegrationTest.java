package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderConfirmedRecorder;
import com.loopers.application.order.OrderOutboxRecordingService;
import com.loopers.domain.payment.ConflictEvidence;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentConflictCode;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import com.loopers.testsupport.RegisteredOrderEvents;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * W7 전환(설계 W7.4-4, O3/O4 의 서비스 수준). 최초 PG 확정이 같은 TX 에 Outbox PENDING 한 행을 남기는지 실제 Spring TX·MySQL 에서 확인한다.
 * HTTP 없이 결제 TX 서비스를 테스트 부모 TX 없이 불러 실제로 commit·rollback 하고, 결과는 새 연결의 SQL 로 읽는다.
 * 발행 포트 대역·AFTER_COMMIT listener 는 이 컨텍스트에 없다. W6 의 포트 호출 시점 기대는 W6OrderConfirmedPublicationIntegrationTest 가 보존한다.
 * Outbox 행은 broker 전달·소비 완료의 근거가 아니다.
 */
@SpringBootTest
@Import({PaymentTxTestConfig.class, RegisteredOrderEvents.Config.class})
@DisplayName("결제 TX 서비스는 최초 PG 확정에서만 같은 TX 에 Outbox 한 행을 남기고, 실패하면 확정과 함께 rollback 한다.")
class OrderConfirmedPublicationIntegrationTest {

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
    private RegisteredOrderEvents registered;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private ApplicationContext applicationContext;

    private ExecutorService workers;
    private final List<CountDownLatch> releases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registered.clear();
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

    @DisplayName("제품 recorder 는 Outbox 기록 서비스 하나이고, 직접 발행 listener·포트 bean 은 없다.")
    @Test
    void soleRecorderIsOutbox() {
        assertAll(
            () -> assertThat(applicationContext.getBeansOfType(OrderConfirmedRecorder.class)).containsOnlyKeys("orderOutboxRecordingService"),
            () -> assertThat(AopUtils.getTargetClass(applicationContext.getBean(OrderConfirmedRecorder.class)))
                .isEqualTo(OrderOutboxRecordingService.class),
            () -> assertThat(applicationContext.containsBean("orderConfirmedEventListener")).isFalse(),
            () -> assertThat(applicationContext.containsBean("kafkaOrderEventPublisher")).isFalse(),
            () -> assertThat(applicationContext.containsBean("orderEventKafkaTemplate")).isFalse()
        );
    }

    enum Path { IMMEDIATE, CALLBACK, RECONCILE }

    private PaymentCheckToken token(Path path, Long orderId, PaymentCheckToken begun, String key) {
        return switch (path) {
            case IMMEDIATE -> begun;
            case CALLBACK -> service.prepareVerification(orderId, null, key).token();
            case RECONCILE -> service.prepareVerification(orderId, USER, null).token();
        };
    }

    private static PaymentObservation.TransactionObserved observed(ObservationSource source, Long orderId, String key,
                                                                   long amount, PgTransactionStatus status) {
        return new PaymentObservation.TransactionObserved(source, key, PaymentModel.providerOrderIdOf(orderId), amount, status);
    }

    private static PaymentObservation.TransactionObserved success(ObservationSource source, Long orderId, String key) {
        return observed(source, orderId, key, 2_000L, PgTransactionStatus.SUCCESS);
    }

    private ProductModel lastProduct;

    private Long draftOrder() {
        lastProduct = fixture.product(1_000L, 10L);
        return fixture.draftOrder(USER, 2L, lastProduct);
    }

    private PaymentCheckToken begin(Long orderId, String key) {
        return service.beginConfirmation(USER, orderId, PaymentTxFixture.command(key)).token();
    }

    private Map<String, Object> orderRow(Long orderId) {
        return jdbcTemplate.queryForMap("SELECT status, paid_amount, confirmed_at FROM orders WHERE id = ?", orderId);
    }

    /** DATETIME(6) 원문을 UTC 로 읽는다. 세션 time zone 변환 없이 저장된 값 그대로다. */
    private Instant storedConfirmedAt(Long orderId) {
        String text = jdbcTemplate.queryForObject(
            "SELECT DATE_FORMAT(confirmed_at, '%Y-%m-%dT%H:%i:%s.%f') FROM orders WHERE id = ?", String.class, orderId);
        return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbcTemplate.queryForList("SELECT * FROM order_confirmed_outbox ORDER BY id");
    }

    private List<Long> outboxOrderIds() {
        return jdbcTemplate.queryForList("SELECT order_id FROM order_confirmed_outbox ORDER BY id", Long.class);
    }

    private CountDownLatch release() {
        CountDownLatch latch = new CountDownLatch(1);
        releases.add(latch);
        return latch;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("장벽 대기").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @DisplayName("즉시 성공·콜백·대사 토큰으로 처음 확정하면 commit 뒤 새 연결에서 CONFIRMED 와 같은 사건의 Outbox PENDING 한 행이 보이고 Spring 이벤트는 등록하지 않는다.")
    @ParameterizedTest
    @EnumSource(Path.class)
    void firstConfirmationCommitsOneOutboxRow(Path path) throws Exception {
        Long orderId = draftOrder();
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        PaymentCheckToken token = token(path, orderId, begun, "tx-" + path);

        PaymentApplyResult result = service.applyObservation(token, success(path == Path.IMMEDIATE
            ? ObservationSource.CREATE : ObservationSource.QUERY, orderId, "tx-" + path));

        Map<String, Object> row = outboxRows().get(0);
        JsonNode payload = objectMapper.readTree((String) row.get("payload"));
        Instant stored = storedConfirmedAt(orderId);
        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(orderRow(orderId)).containsEntry("status", "CONFIRMED").containsEntry("paid_amount", 2_000L),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "SUCCESS").containsEntry("transaction_key", "tx-" + path),
            () -> assertThat(fixture.histories(orderId, "ORDER_RESERVATION")).as("예약 1회").isEqualTo(1L),
            () -> assertThat(fixture.histories(orderId, "ORDER_RELEASE")).isZero(),
            () -> assertThat(outboxRows()).as("Outbox 한 행").hasSize(1),
            () -> assertThat(row).containsEntry("event_id", "order-confirmed:" + orderId).containsEntry("order_id", orderId)
                .containsEntry("message_key", Long.toString(orderId)).containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 0L).containsEntry("cycle_attempt_count", 0)
                .containsEntry("published_at", null).containsEntry("last_error", null),
            () -> assertThat(Instant.parse(payload.path("confirmedAt").asText())).as("payload 확정 시각 = 저장 확정 시각").isEqualTo(stored),
            () -> assertThat(payload.path("items").toString())
                .isEqualTo("[{\"productId\":" + lastProduct.getId() + ",\"quantity\":2}]"),
            () -> assertThat(registered.registrations()).as("W7 제품 경로는 Spring 사건을 등록하지 않음").isEmpty()
        );
    }

    @DisplayName("콜백·대사 토큰으로 동시에 SUCCESS 를 적용해도 Order 잠금으로 Outbox 는 한 행이고 확정 시각·예약은 그대로다.")
    @Test
    void concurrentApplicationsRecordOnce() throws Exception {
        Long orderId = draftOrder();
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        PaymentCheckToken callbackToken = token(Path.CALLBACK, orderId, begun, "tx-C");
        PaymentCheckToken reconcileToken = token(Path.RECONCILE, orderId, begun, null);
        CountDownLatch firstReached = new CountDownLatch(1);
        CountDownLatch firstRelease = release();
        AtomicBoolean firstCaller = new AtomicBoolean(true);
        // 첫 TX 를 확정·Outbox SQL 이 flush 된 commit 직전에 세워 두고, 둘째 TX 가 Order 잠금을 실제로 기다리는 것을 본 뒤에 놓아 준다.
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", orderId, () -> {
            if (firstCaller.getAndSet(false)) {
                firstReached.countDown();
                awaitLatch(firstRelease);
            }
        });

        Future<PaymentApplyResult> first = workers.submit(() ->
            service.applyObservation(callbackToken, success(ObservationSource.QUERY, orderId, "tx-C")));
        awaitLatch(firstReached);
        Future<PaymentApplyResult> second = workers.submit(() ->
            service.applyObservation(reconcileToken, success(ObservationSource.QUERY, orderId, "tx-C")));
        await().atMost(WAIT).until(fixture::someoneWaitsForOrderLock);
        firstRelease.countDown();
        first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        second.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        JsonNode payload = objectMapper.readTree((String) outboxRows().get(0).get("payload"));
        assertAll(
            () -> assertThat(outboxOrderIds()).containsExactly(orderId),
            () -> assertThat(Instant.parse(payload.path("confirmedAt").asText())).isEqualTo(storedConfirmedAt(orderId)),
            () -> assertThat(fixture.histories(orderId, "ORDER_RESERVATION")).isEqualTo(1L)
        );
    }

    @DisplayName("DRAFT·결제 시작(UNKNOWN)·PENDING·확인 불가·충돌·실패 반환·같은 종결 반복·과거 POINT 주문은 새 Outbox 를 만들지 않는다.")
    @Test
    void recordsNothingOutsideFirstConfirmation() {
        Long draft = draftOrder();
        service.prepareConfirmation(USER, draft, PaymentTxFixture.command(PaymentTxFixture.KEY_A));

        Long pending = draftOrder();
        PaymentCheckToken pendingToken = begin(pending, PaymentTxFixture.KEY_B);
        PaymentApplyResult unavailable = service.applyObservation(pendingToken, new PaymentObservation.Unavailable(ObservationSource.CREATE));
        PaymentApplyResult stillPending = service.applyObservation(pendingToken,
            observed(ObservationSource.CREATE, pending, "tx-P", 2_000L, PgTransactionStatus.PENDING));
        PaymentApplyResult conflict = service.applyObservation(pendingToken,
            observed(ObservationSource.CREATE, pending, "tx-P", 1L, PgTransactionStatus.SUCCESS));

        Long failed = draftOrder();
        PaymentApplyResult released = service.applyObservation(begin(failed, KEY_C),
            observed(ObservationSource.CREATE, failed, "tx-F", 2_000L, PgTransactionStatus.FAILED));
        List<Long> beforeConfirm = outboxOrderIds();

        Long confirmed = draftOrder();
        PaymentCheckToken confirmedToken = begin(confirmed, "123e4567-e89b-12d3-a456-426614174003");
        service.applyObservation(confirmedToken, success(ObservationSource.CREATE, confirmed, "tx-OK"));
        List<Map<String, Object>> afterFirst = outboxRows();
        Instant firstConfirmedAt = storedConfirmedAt(confirmed);
        PaymentApplyResult repeated = service.applyObservation(token(Path.RECONCILE, confirmed, confirmedToken, null),
            success(ObservationSource.QUERY, confirmed, "tx-OK"));

        Long point = draftOrder();
        jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED', payment_method = 'POINT', point_amount = 2000, "
            + "paid_amount = 2000 WHERE id = ?", point);
        Throwable pointRejected = catchThrowable(() -> begin(point, "123e4567-e89b-12d3-a456-426614174004"));

        assertAll(
            () -> assertThat(unavailable.outcome()).isEqualTo(PaymentApplyResult.Outcome.UNAVAILABLE),
            () -> assertThat(stillPending.outcome()).isEqualTo(PaymentApplyResult.Outcome.PENDING),
            () -> assertThat(conflict.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(released.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(repeated.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(pointRejected).isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.ORDER_NOT_CONFIRMABLE),
            () -> assertThat(beforeConfirm).as("최초 확정 전 경로의 Outbox").isEmpty(),
            () -> assertThat(outboxOrderIds()).as("최초 확정 1건만").containsExactly(confirmed),
            () -> assertThat(outboxRows()).as("같은 종결 반복은 기존 행을 바꾸지 않음").isEqualTo(afterFirst),
            () -> assertThat(storedConfirmedAt(confirmed)).as("같은 종결 반복은 확정 시각 유지").isEqualTo(firstConfirmedAt),
            () -> assertThat(orderRow(pending)).containsEntry("status", "PAYMENT_PENDING").containsEntry("confirmed_at", null),
            () -> assertThat(orderRow(failed)).containsEntry("status", "PAYMENT_FAILED").containsEntry("confirmed_at", null)
        );
    }

    @DisplayName("Outbox insert·flush 뒤 commit 직전에 실패하면 확정·Outbox 가 함께 rollback 되고, 같은 거래로 다시 확정하면 그때 한 행이 남는다.")
    @Test
    void failureAfterOutboxInsertRollsBackTogether() {
        Long orderId = draftOrder();
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        List<Long> inTransaction = new ArrayList<>();
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", orderId, () -> {
            // 같은 TX 연결에서 실제 insert 가 실행됐는지 본 뒤 실패를 주입한다. 실패한 TX 에서 더 조회하지 않는다.
            inTransaction.add(((Number) entityManagerCount(orderId)).longValue());
            throw new IllegalStateException("commit 직전 실패 주입");
        });

        Throwable failure = catchThrowable(() -> service.applyObservation(begun, success(ObservationSource.CREATE, orderId, "tx-B")));
        Map<String, Object> afterRollback = orderRow(orderId);
        Map<String, Object> paymentAfterRollback = fixture.payment(orderId);
        List<Long> outboxAfterRollback = outboxOrderIds();
        long reservationsAfterRollback = fixture.histories(orderId, "ORDER_RESERVATION");
        probe.clear();
        service.applyObservation(begun, success(ObservationSource.CREATE, orderId, "tx-B"));

        assertAll(
            () -> assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage("commit 직전 실패 주입"),
            () -> assertThat(inTransaction).as("TX 안에서는 Outbox insert 가 실행됨").containsExactly(1L),
            () -> assertThat(outboxAfterRollback).as("rollback 뒤 새 연결의 Outbox").isEmpty(),
            () -> assertThat(afterRollback).containsEntry("status", "PAYMENT_PENDING").containsEntry("paid_amount", null)
                .containsEntry("confirmed_at", null),
            () -> assertThat(paymentAfterRollback).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null),
            () -> assertThat(reservationsAfterRollback).as("TX A 예약 유지").isEqualTo(1L),
            () -> assertThat(outboxOrderIds()).containsExactly(orderId),
            () -> assertThat(orderRow(orderId)).containsEntry("status", "CONFIRMED")
        );
    }

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    /** 현재 TX 의 연결로 Outbox 행 수를 센다(같은 TX 스레드에서만 호출). */
    private Object entityManagerCount(Long orderId) {
        return entityManager.createNativeQuery("SELECT COUNT(*) FROM order_confirmed_outbox WHERE order_id = ?")
            .setParameter(1, orderId).getSingleResult();
    }

    @DisplayName("다른 주문에 묶인 거래 키로 확정하면 Outbox flush 에서 uk_payment_transaction 이 변환된 예외로 드러나 전체 rollback 되고, 충돌 기록 TX 는 Outbox 를 만들지 않는다.")
    @Test
    void paymentUniqueViolationDuringOutboxFlushRollsBack() {
        Long first = draftOrder();
        Long second = draftOrder();
        service.applyObservation(begin(first, PaymentTxFixture.KEY_A), success(ObservationSource.CREATE, first, "tx-SHARED"));
        List<Map<String, Object>> firstOutbox = outboxRows();
        Instant firstConfirmedAt = storedConfirmedAt(first);
        PaymentCheckToken secondToken = begin(second, PaymentTxFixture.KEY_B);
        PaymentObservation.TransactionObserved reused = success(ObservationSource.CREATE, second, "tx-SHARED");

        Throwable violation = catchThrowable(() -> service.applyObservation(secondToken, reused));
        List<Map<String, Object>> outboxAfterViolation = outboxRows();
        Map<String, Object> secondAfterViolation = orderRow(second);
        PaymentApplyResult recorded = service.recordConstraintConflict(secondToken, reused);

        assertAll(
            () -> assertThat(violation).isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThat(classifier.namedUniqueViolation(violation)).hasValue("uk_payment_transaction"),
            () -> assertThat(violation).as("Outbox 저장 단계의 flush 에서 드러남")
                .hasStackTraceContaining("OrderConfirmedOutboxRepositoryImpl.insertAndFlush"),
            () -> assertThat(outboxAfterViolation).as("두 번째 주문 Outbox 없음·첫 주문 행 그대로").isEqualTo(firstOutbox),
            () -> assertThat(secondAfterViolation).containsEntry("status", "PAYMENT_PENDING").containsEntry("confirmed_at", null),
            () -> assertThat(fixture.payment(second)).containsEntry("status", "UNKNOWN"),
            () -> assertThat(fixture.histories(second, "ORDER_RESERVATION")).isEqualTo(1L),
            () -> assertThat(recorded.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(outboxRows()).as("충돌 기록 TX 는 Outbox 를 만들지 않음").isEqualTo(firstOutbox),
            () -> assertThat(fixture.conflicts(second)).singleElement()
                .satisfies(conflict -> assertThat(conflict).containsEntry("code", "TRANSACTION_ALREADY_BOUND")),
            () -> assertThat(storedConfirmedAt(first)).isEqualTo(firstConfirmedAt),
            () -> assertThat(orderRow(first)).containsEntry("status", "CONFIRMED")
        );
    }

    @DisplayName("잘못된 저장 품목으로 사건을 만들 수 없으면 확정 TX 전체가 실패하고 Outbox 가 없다.")
    @Test
    void snapshotFailureRollsBackConfirmation() {
        Long orderId = draftOrder();
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        jdbcTemplate.update("UPDATE order_item SET product_id = 0 WHERE order_id = ?", orderId);

        assertAll(
            () -> assertThatThrownBy(() -> service.applyObservation(begun, success(ObservationSource.CREATE, orderId, "tx-S")))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThat(outboxRows()).isEmpty(),
            () -> assertThat(orderRow(orderId)).containsEntry("status", "PAYMENT_PENDING").containsEntry("confirmed_at", null),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "UNKNOWN")
        );
    }

    @DisplayName("충돌 판정은 Outbox 를 만들지 않는다.")
    @Test
    void conflictDoesNotRecord() {
        Long orderId = draftOrder();
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);

        PaymentApplyResult result = service.applyObservation(begun, new PaymentObservation.Conflict(ObservationSource.CREATE,
            PaymentConflictCode.MULTIPLE_TRANSACTIONS, ConflictEvidence.ofCount(2)));

        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(outboxRows()).isEmpty()
        );
    }
}
