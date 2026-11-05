package com.loopers.application.payment;

import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.order.support.W6OrderConfirmedEventListener;
import com.loopers.infrastructure.order.support.W6OrderEventPublisher;
import com.loopers.infrastructure.order.support.W6PreparationContextInitializer;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * W6 보존(설계 W7.4-4). 출처: 53f912b apps/commerce-api/src/test/java/com/loopers/application/payment/OrderConfirmedPublicationIntegrationTest.java.
 * 허용 변경: 클래스 이름, W6 준비 컨텍스트 초기화기 연결, 포트·listener 타입을 W6 보존 타입으로 연결. 남긴 시나리오·기대값은 원본 그대로다.
 *
 * <p>2026-11-04 승인된 배치 변경(구현 02a)으로 원본 12건 중 공통 결제 규칙 조합 4개를 W7 회귀로 넘겼다. 대응은 다음과 같다.
 * concurrentApplicationsPublishOnce → OrderConfirmedPublicationIntegrationTest.concurrentApplicationsRecordOnce,
 * registersNothingOutsideFirstConfirmation → recordsNothingOutsideFirstConfirmation,
 * snapshotFailureRollsBackConfirmation → snapshotFailureRollsBackConfirmation, conflictDoesNotRegister → conflictDoesNotRecord.
 * 잠금·분기·snapshot·충돌 판정은 W6/W7 이 공유하는 PaymentTransactionService 가 하고 W6 recorder 는 받은 사건을 등록만 하므로,
 * 그 규칙은 W7 쪽이 보호한다. 다만 W6 조립에서 이 네 조합의 등록·포트 호출 횟수를 직접 관찰하는 실행은 없어졌고,
 * W7 의 Outbox 행 수는 W6 포트 횟수의 직접 증거가 아니다.
 * 여기에는 W6 고유 기대만 남긴다: 단일 기록 포트 연결, 포트 호출 시점의 다른 연결 commit 관찰(3경로), rollback 시 포트 0,
 * 실제 commit 제약 실패 시 포트 0, TX 밖 등록 무시, commit 뒤 listener 오류의 안전 로그(합계 8건).
 * W7 제품 경로의 Outbox 기대는 OrderConfirmedPublicationIntegrationTest 와 OrderOutboxAtomicityIntegrationTest 가 맡는다.
 *
 * <p>최초 PG 확정 사건의 등록과 commit 뒤 포트 호출을 실제 Spring TX·MySQL 에서 확인한다(P2·P3 의 서비스 수준).
 * HTTP 를 거치지 않고 결제 TX 서비스를 테스트 부모 TX 없이 불러 실제로 commit·rollback 한다. 포트는 기록용 fake 라 broker 근거가 아니다.
 * 사건 등록은 테스트 전용 일반 listener 로, 포트 호출은 fake 의 기록으로 따로 센다.
 */
@SpringBootTest
@ContextConfiguration(initializers = W6PreparationContextInitializer.class)
@Import({PaymentTxTestConfig.class, RegisteredOrderEvents.Config.class})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("[W6 보존] 최초 PG 확정 사건은 실제 commit 뒤에만 발행 포트를 한 번 부르고, rollback·TX 밖 등록·listener 오류는 확정을 바꾸지 않는다.")
class W6OrderConfirmedPublicationIntegrationTest {

    @TestBean(name = "kafkaOrderEventPublisher", methodName = RecordingOrderEventPublisher.FACTORY, enforceOverride = true)
    private W6OrderEventPublisher orderEventPublisher;

    @BeforeEach
    void resetRecordedOrderEvents() {
        recordedOrderEvents().reset();
    }

    private RecordingOrderEventPublisher recordedOrderEvents() {
        return (RecordingOrderEventPublisher) orderEventPublisher;
    }

    private static final Long USER = 1L;

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
    private W6OrderConfirmedEventListener listener;

    private CommittedOrderObserver observer;

    @BeforeEach
    void setUp() {
        registered.clear();
        observer = new CommittedOrderObserver(transactionManager, entityManager);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        try {
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
            () -> assertThat(applicationContext.getBeansOfType(W6OrderEventPublisher.class)).containsOnlyKeys("kafkaOrderEventPublisher"),
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

}
