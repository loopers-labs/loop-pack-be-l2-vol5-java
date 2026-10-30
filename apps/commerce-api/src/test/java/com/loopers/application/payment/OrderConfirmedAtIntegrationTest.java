package com.loopers.application.payment;

import com.loopers.config.OrderEventKafkaConfig;
import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.SettableClock;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import com.loopers.testsupport.RecordingOrderEventPublisher;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 최초 PG 확정 시각의 저장을 실제 MySQL 에서 확인한다. HTTP 를 거치지 않고 결제 TX 서비스를 테스트 부모 TX 없이 불러 실제로 commit 한다.
 * 같은 Entity 를 다시 보지 않도록 결과는 새 연결의 SQL 과 새 EntityManager 로 읽는다.
 */
@SpringBootTest
@Import(PaymentTxTestConfig.class)
@DisplayName("결제 TX 서비스는 최초 PG 확정 시각을 UTC 마이크로초로 한 번만 저장한다.")
class OrderConfirmedAtIntegrationTest {

    @TestBean(name = "kafkaOrderEventPublisher", methodName = RecordingOrderEventPublisher.FACTORY, enforceOverride = true)
    private OrderEventPublisher orderEventPublisher;

    private static final Long USER = 1L;
    private static final Duration WAIT = Duration.ofSeconds(10);
    /** 나노초와 서울 zone 을 일부러 넣어 서비스의 UTC 정규화·마이크로초 절삭을 확인한다. */
    private static final Instant FIRST_INPUT = Instant.parse("2026-10-28T07:00:00.123456789Z");
    private static final Instant FIRST_STORED = Instant.parse("2026-10-28T07:00:00.123456Z");
    private static final Instant LATER_INPUT = Instant.parse("2026-10-28T08:30:00.654321987Z");
    private static final Instant LATER_STORED = Instant.parse("2026-10-28T08:30:00.654321Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final SettableClock CLOCK = new SettableClock(FIRST_INPUT, SEOUL);

    @TestBean(name = OrderEventKafkaConfig.ORDER_CONFIRMATION_CLOCK, methodName = "orderConfirmationClock")
    private Clock orderConfirmationClock;

    @Autowired
    private PaymentTransactionService service;
    @Autowired
    private PaymentTxFixture fixture;
    @Autowired
    private ControllableProbe probe;
    @Autowired
    private ConstraintViolationClassifier classifier;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private OrderJpaRepository orderJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ExecutorService workers;
    private final List<CountDownLatch> releases = new ArrayList<>();

    static Clock orderConfirmationClock() {
        return CLOCK;
    }

    @BeforeEach
    void setUp() {
        CLOCK.set(FIRST_INPUT, SEOUL);
        CLOCK.resetReads();
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

    /** 서비스 수준의 결과 반영 경로. 즉시 생성 결과는 CREATE 토큰, 콜백·대사는 각자의 준비 방식으로 만든 QUERY 토큰이다. */
    enum Path { IMMEDIATE, CALLBACK, RECONCILE }

    private PaymentCheckToken token(Path path, Long orderId, PaymentCheckToken begun, String key) {
        return switch (path) {
            case IMMEDIATE -> begun;
            case CALLBACK -> service.prepareVerification(orderId, null, key).token();
            case RECONCILE -> service.prepareVerification(orderId, USER, null).token();
        };
    }

    private static ObservationSource source(Path path) {
        return path == Path.IMMEDIATE ? ObservationSource.CREATE : ObservationSource.QUERY;
    }

    private static PaymentObservation.TransactionObserved success(ObservationSource source, Long orderId, String key, long amount) {
        return new PaymentObservation.TransactionObserved(source, key, PaymentModel.providerOrderIdOf(orderId), amount,
            PgTransactionStatus.SUCCESS);
    }

    private Long pendingOrder(ProductModel product) {
        return fixture.draftOrder(USER, 1L, product);
    }

    private PaymentCheckToken begin(Long orderId, String attemptKey) {
        return service.beginConfirmation(USER, orderId, PaymentTxFixture.command(attemptKey)).token();
    }

    /** DB 에 저장된 그대로의 DATETIME(6) 문자열. 세션 time zone 변환이 없는 열 값이다. */
    private String storedText(Long orderId) {
        return jdbcTemplate.queryForObject(
            "SELECT DATE_FORMAT(confirmed_at, '%Y-%m-%d %H:%i:%s.%f') FROM orders WHERE id = ?", String.class, orderId);
    }

    private Map<String, Object> orderRow(Long orderId) {
        return jdbcTemplate.queryForMap("SELECT status, paid_amount, confirmed_at FROM orders WHERE id = ?", orderId);
    }

    /** 1차 캐시를 공유하지 않는 새 EntityManager 로 다시 읽는다. */
    private ZonedDateTime reloadConfirmedAt(Long orderId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return entityManager.find(OrderModel.class, orderId).getConfirmedAt();
        } finally {
            entityManager.close();
        }
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

    @DisplayName("실제 생성 DDL 의 orders.confirmed_at 은 NULL 허용 DATETIME(6) 이다.")
    @Test
    void columnIsNullableDatetime6() {
        Map<String, Object> column = jdbcTemplate.queryForMap("""
            SELECT COLUMN_TYPE, IS_NULLABLE, DATETIME_PRECISION, COLUMN_DEFAULT FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'confirmed_at'
            """);

        assertAll(
            () -> assertThat(column.get("COLUMN_TYPE")).isEqualTo("datetime(6)"),
            () -> assertThat(column.get("IS_NULLABLE")).isEqualTo("YES"),
            () -> assertThat(((Number) column.get("DATETIME_PRECISION")).intValue()).isEqualTo(6),
            () -> assertThat(column.get("COLUMN_DEFAULT")).isNull()
        );
    }

    @DisplayName("즉시 성공·콜백·대사 토큰으로 처음 확정하면 서울 나노초 시각이 UTC 마이크로초로 저장되고 다시 읽어도 같은 순간이다.")
    @ParameterizedTest
    @EnumSource(Path.class)
    void storesTruncatedUtcInstantOnFirstConfirmation(Path path) {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = pendingOrder(product);
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        PaymentCheckToken token = token(path, orderId, begun, "tx-" + path);
        Map<String, Object> beforeConfirm = orderRow(orderId);

        PaymentApplyResult result = service.applyObservation(token, success(source(path), orderId, "tx-" + path, 1_000L));
        ZonedDateTime reloaded = reloadConfirmedAt(orderId);
        System.out.printf("[P4] path=%s reloaded=%s zone=%s zoneClass=%s normalized=%s%n", path, reloaded,
            reloaded.getZone(), reloaded.getZone().getClass().getSimpleName(), reloaded.getZone().normalized());

        assertAll(
            () -> assertThat(beforeConfirm.get("confirmed_at")).as("확정 전에는 없다").isNull(),
            () -> assertThat(result.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(CLOCK.reads()).as("최초 CONFIRM 에서만 한 번 읽는다").isEqualTo(1),
            () -> assertThat(orderRow(orderId)).containsEntry("status", "CONFIRMED").containsEntry("paid_amount", 1_000L),
            () -> assertThat(storedText(orderId)).as("UTC 로 저장한 마이크로초 값").isEqualTo("2026-10-28 07:00:00.123456"),
            () -> assertThat(reloaded.toInstant()).isEqualTo(FIRST_STORED),
            () -> assertThat(reloaded.getNano() % 1_000).isZero(),
            () -> assertThat(reloaded.getZone().normalized()).isEqualTo(ZoneOffset.UTC),
            () -> assertThat(OrderModel.isUtcMicros(reloaded)).isTrue(),
            () -> assertThat(result.order().status()).isEqualTo("CONFIRMED")
        );
    }

    @DisplayName("확정 뒤 같은 SUCCESS 를 다시 적용하면 Clock 이 바뀌어도 최초 확정 시각이 그대로다.")
    @Test
    void repeatedFinalObservationKeepsFirstTime() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = pendingOrder(product);
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        service.applyObservation(begun, success(ObservationSource.CREATE, orderId, "tx-R", 1_000L));
        CLOCK.set(LATER_INPUT, ZoneOffset.UTC);

        PaymentApplyResult callback = service.applyObservation(token(Path.CALLBACK, orderId, begun, "tx-R"),
            success(ObservationSource.QUERY, orderId, "tx-R", 1_000L));
        PaymentApplyResult reconcile = service.applyObservation(token(Path.RECONCILE, orderId, begun, null),
            success(ObservationSource.QUERY, orderId, "tx-R", 1_000L));
        PaymentApplyResult lateCreate = service.applyObservation(begun, success(ObservationSource.CREATE, orderId, "tx-R", 1_000L));

        assertAll(
            () -> assertThat(List.of(callback.outcome(), reconcile.outcome(), lateCreate.outcome()))
                .containsOnly(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(CLOCK.reads()).isEqualTo(1),
            () -> assertThat(storedText(orderId)).isEqualTo("2026-10-28 07:00:00.123456"),
            () -> assertThat(reloadConfirmedAt(orderId).toInstant()).isEqualTo(FIRST_STORED),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(9L)
        );
    }

    @DisplayName("콜백·대사 토큰으로 동시에 SUCCESS 를 적용해도 Order 잠금으로 확정은 한 번이고 최초 시각이 그대로다.")
    @Test
    void concurrentApplicationsConfirmOnce() throws Exception {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = pendingOrder(product);
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
            service.applyObservation(callbackToken, success(ObservationSource.QUERY, orderId, "tx-C", 1_000L)));
        awaitLatch(firstReached);
        CLOCK.set(LATER_INPUT, ZoneOffset.UTC);
        Future<PaymentApplyResult> second = workers.submit(() ->
            service.applyObservation(reconcileToken, success(ObservationSource.QUERY, orderId, "tx-C", 1_000L)));
        await().atMost(WAIT).until(fixture::someoneWaitsForOrderLock);
        firstRelease.countDown();

        PaymentApplyResult a = first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        PaymentApplyResult b = second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertAll(
            () -> assertThat(a.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(b.outcome()).isEqualTo(PaymentApplyResult.Outcome.FINAL),
            () -> assertThat(CLOCK.reads()).as("잠금 뒤 기다린 쪽은 FINAL_UNCHANGED 로 시각을 만들지 않는다").isEqualTo(1),
            () -> assertThat(storedText(orderId)).isEqualTo("2026-10-28 07:00:00.123456"),
            () -> assertThat(fixture.stock(product.getId())).isEqualTo(9L),
            () -> assertThat(fixture.histories(orderId, "ORDER_RELEASE")).isZero()
        );
    }

    @DisplayName("확정 SQL 뒤 commit 전에 실패하면 시각까지 rollback 되고, 다시 확정하면 그때의 시각이 저장된다.")
    @Test
    void rollbackLeavesNoConfirmedAt() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = pendingOrder(product);
        PaymentCheckToken begun = begin(orderId, PaymentTxFixture.KEY_A);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", orderId, () -> {
            throw new IllegalStateException("commit 직전 실패 주입");
        });

        Throwable failure = catchThrowable(() ->
            service.applyObservation(begun, success(ObservationSource.CREATE, orderId, "tx-B", 1_000L)));
        Map<String, Object> afterRollback = orderRow(orderId);
        probe.clear();
        CLOCK.set(LATER_INPUT, SEOUL);
        service.applyObservation(begun, success(ObservationSource.CREATE, orderId, "tx-B", 1_000L));

        assertAll(
            () -> assertThat(failure).isInstanceOf(IllegalStateException.class),
            () -> assertThat(afterRollback).containsEntry("status", "PAYMENT_PENDING").containsEntry("paid_amount", null)
                .containsEntry("confirmed_at", null),
            () -> assertThat(storedText(orderId)).isEqualTo("2026-10-28 08:30:00.654321"),
            () -> assertThat(reloadConfirmedAt(orderId).toInstant()).isEqualTo(LATER_STORED)
        );
    }

    @DisplayName("다른 결제에 결합된 거래로 확정하면 commit 의 unique 위반으로 시각 없이 rollback 되고, 충돌 기록도 시각을 남기지 않는다.")
    @Test
    void commitConstraintFailureLeavesNoConfirmedAt() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long first = pendingOrder(product);
        Long second = pendingOrder(product);
        service.applyObservation(begin(first, PaymentTxFixture.KEY_A), success(ObservationSource.CREATE, first, "tx-SHARED", 1_000L));
        PaymentCheckToken secondToken = begin(second, PaymentTxFixture.KEY_B);
        PaymentObservation.TransactionObserved reused = success(ObservationSource.CREATE, second, "tx-SHARED", 1_000L);

        Throwable violation = catchThrowable(() -> service.applyObservation(secondToken, reused));
        Map<String, Object> afterRollback = orderRow(second);
        PaymentApplyResult recorded = service.recordConstraintConflict(secondToken, reused);

        assertAll(
            () -> assertThat(violation).isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThat(classifier.namedUniqueViolation(violation)).hasValue("uk_payment_transaction"),
            () -> assertThat(afterRollback).containsEntry("status", "PAYMENT_PENDING").containsEntry("confirmed_at", null),
            () -> assertThat(recorded.outcome()).isEqualTo(PaymentApplyResult.Outcome.CONFLICT),
            () -> assertThat(orderRow(second)).containsEntry("status", "PAYMENT_PENDING").containsEntry("confirmed_at", null),
            () -> assertThat(storedText(first)).isEqualTo("2026-10-28 07:00:00.123456")
        );
    }

    @DisplayName("CONFIRMED 에 시각이 없거나 대기·실패에 시각이 있으면 저장 정합성 오류이고 아무것도 바뀌지 않는다.")
    @Test
    void storedConsistencyChecksConfirmedAtByStatus() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long confirmed = pendingOrder(product);
        service.applyObservation(begin(confirmed, PaymentTxFixture.KEY_A), success(ObservationSource.CREATE, confirmed, "tx-1", 1_000L));
        Long pending = pendingOrder(product);
        PaymentCheckToken pendingToken = begin(pending, PaymentTxFixture.KEY_B);
        Long failed = pendingOrder(product);
        service.applyObservation(begin(failed, "123e4567-e89b-12d3-a456-426614174002"),
            new PaymentObservation.TransactionObserved(ObservationSource.CREATE, "tx-3", PaymentModel.providerOrderIdOf(failed),
                1_000L, PgTransactionStatus.FAILED));
        jdbcTemplate.update("UPDATE orders SET confirmed_at = NULL WHERE id = ?", confirmed);
        jdbcTemplate.update("UPDATE orders SET confirmed_at = '2026-10-28 07:00:00.000001' WHERE id IN (?, ?)", pending, failed);
        List<Map<String, Object>> ordersBefore = jdbcTemplate.queryForList("SELECT * FROM orders ORDER BY id");
        List<Map<String, Object>> paymentsBefore = jdbcTemplate.queryForList("SELECT * FROM payment ORDER BY id");
        CLOCK.resetReads();

        assertAll(
            () -> assertThatThrownBy(() -> service.prepareVerification(confirmed, USER, null))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(() -> service.prepareVerification(pending, USER, null))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(() -> service.applyObservation(pendingToken,
                    success(ObservationSource.CREATE, pending, "tx-2", 1_000L)))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(() -> service.prepareVerification(failed, USER, null))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThat(jdbcTemplate.queryForList("SELECT * FROM orders ORDER BY id")).isEqualTo(ordersBefore),
            () -> assertThat(jdbcTemplate.queryForList("SELECT * FROM payment ORDER BY id")).isEqualTo(paymentsBefore),
            () -> assertThat(CLOCK.reads()).isZero()
        );
    }

    @DisplayName("PG 실패 확정은 확정 시각을 만들지 않는다.")
    @Test
    void failureHasNoConfirmedAt() {
        ProductModel product = fixture.product(1_000L, 10L);
        Long orderId = pendingOrder(product);

        service.applyObservation(begin(orderId, PaymentTxFixture.KEY_A),
            new PaymentObservation.TransactionObserved(ObservationSource.CREATE, "tx-F", PaymentModel.providerOrderIdOf(orderId),
                1_000L, PgTransactionStatus.FAILED));

        assertAll(
            () -> assertThat(orderRow(orderId)).containsEntry("status", "PAYMENT_FAILED").containsEntry("confirmed_at", null),
            () -> assertThat(CLOCK.reads()).isZero(),
            () -> assertThat(orderJpaRepository.findById(orderId)).get().extracting(OrderModel::getConfirmedAt).isNull()
        );
    }
}
