package com.loopers.application.payment;

import com.loopers.application.order.OrderConfirmedEventListener;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderEventPublisher;
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
import com.loopers.testsupport.CommittedOrderObserver;
import com.loopers.testsupport.RecordingOrderEventPublisher;
import com.loopers.testsupport.RegisteredOrderEvents;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 최초 PG 확정 사건의 등록과 commit 뒤 포트 호출을 실제 Spring TX·MySQL 에서 확인한다(P2·P3 의 서비스 수준).
 * HTTP 를 거치지 않고 결제 TX 서비스를 테스트 부모 TX 없이 불러 실제로 commit·rollback 한다. 포트는 기록용 fake 라 broker 근거가 아니다.
 * 사건 등록은 테스트 전용 일반 listener 로, 포트 호출은 fake 의 기록으로 따로 센다.
 */
@SpringBootTest
@Import({PaymentTxTestConfig.class, RegisteredOrderEvents.Config.class})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("결제 TX 서비스는 최초 PG 확정에서만 사건을 등록하고, 실제 commit 뒤에만 발행 포트를 한 번 부른다.")
class OrderConfirmedPublicationIntegrationTest {

    @TestBean(name = "kafkaOrderEventPublisher", methodName = RecordingOrderEventPublisher.FACTORY, enforceOverride = true)
    private OrderEventPublisher orderEventPublisher;

    @BeforeEach
    void resetRecordedOrderEvents() {
        recordedOrderEvents().reset();
    }

    private RecordingOrderEventPublisher recordedOrderEvents() {
        return (RecordingOrderEventPublisher) orderEventPublisher;
    }

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
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private OrderConfirmedEventListener listener;

    private CommittedOrderObserver observer;
    private ExecutorService workers;
    private final List<CountDownLatch> releases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registered.clear();
        observer = new CommittedOrderObserver(transactionManager, entityManager);
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        try {
            releases.forEach(CountDownLatch::countDown);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS)).as("TX 스레드 종료").isTrue();
            observer.close();
        } finally {
            probe.clear();
            databaseCleanUp.truncateAllTables();
        }
    }

    @DisplayName("이 컨텍스트의 발행 포트는 기록용 fake 하나이고, commit 뒤 listener 도 그 fake 를 부른다.")
    @Test
    void portIsSingleRecordingFake() {
        assertAll(
            () -> assertThat(applicationContext.getBeansOfType(OrderEventPublisher.class)).containsOnlyKeys("kafkaOrderEventPublisher"),
            () -> assertThat(orderEventPublisher).isInstanceOf(RecordingOrderEventPublisher.class),
            () -> assertThat(ReflectionTestUtils.getField(listener, "publisher")).isSameAs(orderEventPublisher)
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

    private Long draftOrder() {
        ProductModel product = fixture.product(1_000L, 10L);
        return fixture.draftOrder(USER, 2L, product);
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

    private List<OrderConfirmed> registeredEvents() {
        return registered.registrations().stream().map(RegisteredOrderEvents.Registration::event).toList();
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

    @DisplayName("즉시 성공·콜백·대사 토큰으로 처음 확정하면 사건이 TX 안에서 한 번 등록되고, 포트가 불린 시점에 다른 스레드·연결의 새 TX 가 CONFIRMED 와 확정 시각을 본다.")
    @ParameterizedTest
    @EnumSource(Path.class)
    void portSeesCommittedConfirmationFromAnotherConnection(Path path) {
        Long orderId = draftOrder();
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        PaymentCheckToken token = token(path, orderId, begun, "tx-" + path);
        AtomicLong originalConnection = new AtomicLong(-1);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", orderId,
            () -> originalConnection.set(CommittedOrderObserver.currentConnectionId(entityManager)));
        recordedOrderEvents().onPublish(observer::observe);

        PaymentApplyResult result = service.applyObservation(token, success(path == Path.IMMEDIATE
            ? ObservationSource.CREATE : ObservationSource.QUERY, orderId, "tx-" + path));

        OrderConfirmed event = recordedOrderEvents().published().get(0);
        CommittedOrderObserver.Observation seen = observer.observations().get(0);
        Instant stored = storedConfirmedAt(orderId);
        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(registered.registrations()).singleElement()
                .satisfies(registration -> assertThat(registration.inActualTransaction()).as("TX 안 등록").isTrue()),
            () -> assertThat(recordedOrderEvents().published()).as("포트 호출 1회").hasSize(1),
            () -> assertThat(event).isEqualTo(registeredEvents().get(0)),
            () -> assertThat(event.eventId()).isEqualTo("order-confirmed:" + orderId),
            () -> assertThat(event.items()).hasSize(1).allSatisfy(item -> assertThat(item.quantity()).isEqualTo(2L)),
            () -> assertThat(observer.failures()).as("관찰 실패 없음").isEmpty(),
            () -> assertThat(seen.orderStatus()).isEqualTo("CONFIRMED"),
            () -> assertThat(seen.confirmedAt().toInstant()).isEqualTo(event.confirmedAt().toInstant()),
            () -> assertThat(stored).isEqualTo(event.confirmedAt().toInstant()),
            () -> assertThat(seen.observerTxActive()).isTrue(),
            () -> assertThat(seen.observerThread()).isNotEqualTo(seen.callerThread()),
            () -> assertThat(originalConnection.get()).as("원 TX 연결 관측").isPositive(),
            () -> assertThat(seen.connectionId()).as("원 TX 와 다른 연결").isNotEqualTo(originalConnection.get())
        );
    }

    @DisplayName("콜백·대사 토큰으로 동시에 SUCCESS 를 적용해도 Order 잠금으로 사건 등록과 포트 호출은 한 번이고 확정 시각은 그대로다.")
    @Test
    void concurrentApplicationsPublishOnce() throws Exception {
        Long orderId = draftOrder();
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        PaymentCheckToken callbackToken = token(Path.CALLBACK, orderId, begun, "tx-C");
        PaymentCheckToken reconcileToken = token(Path.RECONCILE, orderId, begun, null);
        CountDownLatch firstReached = new CountDownLatch(1);
        CountDownLatch firstRelease = release();
        AtomicBoolean firstCaller = new AtomicBoolean(true);
        // 첫 TX 를 확정 SQL 이 flush 된 commit 직전에 세워 두고, 둘째 TX 가 Order 잠금을 실제로 기다리는 것을 본 뒤에 놓아 준다.
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

        assertAll(
            () -> assertThat(registered.registrations()).hasSize(1),
            () -> assertThat(recordedOrderEvents().published()).hasSize(1),
            () -> assertThat(storedConfirmedAt(orderId))
                .isEqualTo(recordedOrderEvents().published().get(0).confirmedAt().toInstant())
        );
    }

    @DisplayName("DRAFT·결제 시작(UNKNOWN)·PENDING·확인 불가·충돌·실패 반환·같은 종결 반복·과거 POINT 주문은 새 사건을 등록하지 않는다.")
    @Test
    void registersNothingOutsideFirstConfirmation() {
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
        List<RegisteredOrderEvents.Registration> beforeConfirm = registered.registrations();

        Long confirmed = draftOrder();
        PaymentCheckToken confirmedToken = begin(confirmed, "123e4567-e89b-12d3-a456-426614174003");
        service.applyObservation(confirmedToken, success(ObservationSource.CREATE, confirmed, "tx-OK"));
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
            () -> assertThat(beforeConfirm).as("최초 확정 전 경로의 등록").isEmpty(),
            () -> assertThat(registeredEvents()).as("최초 확정 1건만").extracting(OrderConfirmed::orderId).containsExactly(confirmed),
            () -> assertThat(recordedOrderEvents().published()).extracting(OrderConfirmed::orderId).containsExactly(confirmed),
            () -> assertThat(orderRow(pending)).containsEntry("status", "PAYMENT_PENDING").containsEntry("confirmed_at", null),
            () -> assertThat(orderRow(failed)).containsEntry("status", "PAYMENT_FAILED").containsEntry("confirmed_at", null)
        );
    }

    @DisplayName("사건을 등록한 뒤 commit 직전에 실패하면 업무 전체가 rollback 되고 포트는 불리지 않으며, 다시 확정하면 그때 한 번 불린다.")
    @Test
    void rollbackAfterRegistrationSkipsPort() {
        Long orderId = draftOrder();
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", orderId, () -> {
            throw new IllegalStateException("commit 직전 실패 주입");
        });

        Throwable failure = catchThrowable(() -> service.applyObservation(begun, success(ObservationSource.CREATE, orderId, "tx-B")));
        Map<String, Object> afterRollback = orderRow(orderId);
        Map<String, Object> paymentAfterRollback = fixture.payment(orderId);
        int registrationsAfterRollback = registered.registrations().size();
        int publishedAfterRollback = recordedOrderEvents().published().size();
        probe.clear();
        service.applyObservation(begun, success(ObservationSource.CREATE, orderId, "tx-B"));

        assertAll(
            () -> assertThat(failure).isInstanceOf(IllegalStateException.class),
            () -> assertThat(registrationsAfterRollback).as("등록은 됐다").isEqualTo(1),
            () -> assertThat(publishedAfterRollback).as("rollback 이면 포트 호출 없음").isZero(),
            () -> assertThat(afterRollback).containsEntry("status", "PAYMENT_PENDING").containsEntry("paid_amount", null)
                .containsEntry("confirmed_at", null),
            () -> assertThat(paymentAfterRollback).containsEntry("status", "UNKNOWN").containsEntry("transaction_key", null),
            () -> assertThat(registered.registrations()).hasSize(2),
            () -> assertThat(recordedOrderEvents().published()).singleElement()
                .satisfies(event -> assertThat(event.orderId()).isEqualTo(orderId))
        );
    }

    @DisplayName("사건 등록 뒤 commit 의 unique 위반으로 rollback 되면 포트는 불리지 않고, 충돌 기록 TX 도 사건을 등록하지 않는다.")
    @Test
    void commitConstraintFailureSkipsPort() {
        Long first = draftOrder();
        Long second = draftOrder();
        service.applyObservation(begin(first, PaymentTxFixture.KEY_A), success(ObservationSource.CREATE, first, "tx-SHARED"));
        PaymentCheckToken secondToken = begin(second, PaymentTxFixture.KEY_B);
        PaymentObservation.TransactionObserved reused = success(ObservationSource.CREATE, second, "tx-SHARED");

        Throwable violation = catchThrowable(() -> service.applyObservation(secondToken, reused));
        List<OrderConfirmed> registeredAfterViolation = registeredEvents();
        PaymentApplyResult recorded = service.recordConstraintConflict(secondToken, reused);

        assertAll(
            () -> assertThat(violation).isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThat(classifier.namedUniqueViolation(violation)).hasValue("uk_payment_transaction"),
            () -> assertThat(registeredAfterViolation).extracting(OrderConfirmed::orderId).containsExactly(first, second),
            () -> assertThat(recorded.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(registeredEvents()).as("충돌 기록 TX 는 등록하지 않음").hasSize(2),
            () -> assertThat(recordedOrderEvents().published()).extracting(OrderConfirmed::orderId).containsExactly(first),
            () -> assertThat(orderRow(second)).containsEntry("status", "PAYMENT_PENDING").containsEntry("confirmed_at", null)
        );
    }

    @DisplayName("실제 TX 밖에서 등록한 사건은 AFTER_COMMIT listener 가 실행하지 않는다.")
    @Test
    void eventOutsideTransactionIsIgnored() {
        OrderConfirmed event = new OrderConfirmed(42L, ZonedDateTime.ofInstant(Instant.parse("2026-10-28T07:00:00Z"), ZoneOffset.UTC),
            List.of(new OrderConfirmed.Item(7L, 1L)));

        eventPublisher.publishEvent(event);

        assertAll(
            () -> assertThat(registered.registrations()).singleElement()
                .satisfies(registration -> assertThat(registration.inActualTransaction()).isFalse()),
            () -> assertThat(recordedOrderEvents().published()).isEmpty()
        );
    }

    @DisplayName("commit 뒤 포트 외곽에서 예외가 나면 LISTENER_CALL 안전 로그만 남기고 확정 결과·응답은 그대로다.")
    @Test
    void listenerFailureKeepsConfirmation(CapturedOutput output) {
        Long orderId = draftOrder();
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        recordedOrderEvents().onPublish(event -> {
            throw new IllegalStateException("민감한-원문-메시지", new IllegalArgumentException("원인-메시지"));
        });

        PaymentApplyResult result = service.applyObservation(begun, success(ObservationSource.CREATE, orderId, "tx-L"));

        String failureLog = output.getOut().lines().filter(line -> line.contains("ORDER_CONFIRMED_SEND_FAILURE")).findFirst().orElse("");
        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(result.order().status()).isEqualTo("CONFIRMED"),
            () -> assertThat(orderRow(orderId)).containsEntry("status", "CONFIRMED"),
            () -> assertThat(recordedOrderEvents().published()).hasSize(1),
            () -> assertThat(failureLog).contains("stage=LISTENER_CALL", "eventId=order-confirmed:" + orderId,
                "orderId=" + orderId, "exceptionType=java.lang.IllegalStateException",
                "causeType=java.lang.IllegalArgumentException", "topic=order-events"),
            () -> assertThat(output.getAll()).doesNotContain("민감한-원문-메시지", "원인-메시지")
        );
    }

    @DisplayName("잘못된 저장 품목으로 사건을 만들 수 없으면 확정 TX 전체가 실패하고 사건 등록·포트 호출이 없다.")
    @Test
    void snapshotFailureRollsBackConfirmation() {
        Long orderId = draftOrder();
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        jdbcTemplate.update("UPDATE order_item SET product_id = 0 WHERE order_id = ?", orderId);

        assertAll(
            () -> assertThatThrownBy(() -> service.applyObservation(begun, success(ObservationSource.CREATE, orderId, "tx-S")))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThat(registered.registrations()).isEmpty(),
            () -> assertThat(recordedOrderEvents().published()).isEmpty(),
            () -> assertThat(orderRow(orderId)).containsEntry("status", "PAYMENT_PENDING").containsEntry("confirmed_at", null),
            () -> assertThat(fixture.payment(orderId)).containsEntry("status", "UNKNOWN")
        );
    }

    @DisplayName("충돌 판정은 사건을 만들지 않는다.")
    @Test
    void conflictDoesNotRegister() {
        Long orderId = draftOrder();
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);

        PaymentApplyResult result = service.applyObservation(begun, new PaymentObservation.Conflict(ObservationSource.CREATE,
            PaymentConflictCode.MULTIPLE_TRANSACTIONS, ConflictEvidence.ofCount(2)));

        assertAll(
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(registered.registrations()).isEmpty(),
            () -> assertThat(recordedOrderEvents().published()).isEmpty()
        );
    }
}
