package com.loopers.domains.payment.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.common.domain.Money;
import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.domain.PaymentRecord;
import com.loopers.domains.payment.domain.RefundCase;
import com.loopers.domains.payment.domain.RefundCase.MoneyStatus;
import com.loopers.domains.payment.domain.RefundCase.Owner;
import com.loopers.domains.payment.out.PaymentRefundCasePersistenceAdapter;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 결론 저장 트랜잭션(추적 회차·관리자 사람 기록)이 실패하면 상태도 counter도 남지 않아야 한다(counter는 commit 뒤에만 센다).
// 실패 주입은 테스트 설정의 저장소에서 한다: 결론 상태 저장 SQL을 flush로 DB에 보낸 뒤 commit 직전(beforeCommit)에 예외를 던지고,
// 서비스 트랜잭션이 끝난 뒤 새로 읽는다. save 안에서 던지면 저장 뒤에 셈을 등록하는 경로(이관)는 셈 코드에 닿지 못해 판별력이 없다.
// 전용 테스트 설정이라 캐시에 남으면 DB 연결을 계속 붙잡는다(전체 검사에서 Too many connections). 클래스가 끝나면 닫는다.
@DirtiesContext
@SpringBootTest
@Import(RefundTrackingRollbackIntegrationTest.FailureInjectionConfig.class)
class RefundTrackingRollbackIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final long ORDER_ID = 3001;
    private static final String PROVIDER_ORDER_ID = String.format("LP-ORD-%010d", ORDER_ID);
    private static final String OUTCOME_METRIC = "commerce.payment.refund_outcome";
    private static final String ASSIGNED_METRIC = "commerce.payment.refund_person_assigned";
    private static final String CHECK_METRIC = "commerce.payment.refund_check";

    @Autowired private RefundTrackingService refundTracking;
    @Autowired private RefundPersonResolutionService personResolution;
    @Autowired private FailingRefundCases failingRefundCases;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(RefundTrackingService.class);
    private ListAppender<ILoggingEvent> logs;
    private TestPgServer server;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void clean() throws Exception {
        serviceLogger.detachAppender(logs);
        failingRefundCases.disarm();
        pg.close();
        cleanup.truncateAllTables();
    }

    @ParameterizedTest(name = "장면={0} → 실패한 저장={1}/{2}")
    @CsvSource({"failed, NO_CHARGE, AUTO, no_charge/system", "deadline, CHECKING, PERSON, assigned/handoff_deadline"})
    @DisplayName("청구 없음·사람 담당 저장이 flush되고 셈 등록까지 지난 뒤 commit 직전에 실패해 rollback되면 대상은 확인 중·자동 담당 그대로이고 counter는 늘지 않으며 건별 실패만 ERROR로 남는다")
    void doesNotCountRolledBackConclusion(String scene, MoneyStatus failingMoney, Owner failingOwner, String counter) {
        failingRefundCases.save(RefundCase.restore(ORDER_ID, TestPgFixture.PROVIDER_ID, PROVIDER_ORDER_ID, new Money(1000),
            MoneyStatus.CHECKING, null, Owner.AUTO, null, null, null, null, PaymentRecord.ProviderStatus.PENDING, null, NOW,
            NOW.plus(RefundCase.HANDOFF_AFTER), NOW.plus(RefundCase.PROMISE_AFTER), NOW, 0, null, null));
        server.addTransaction(PROVIDER_ORDER_ID, 1000, "deadline".equals(scene) ? "PENDING" : "FAILED");
        if ("deadline".equals(scene)) {
            clock.set(NOW.plus(Duration.ofDays(2)));
        }
        double before = outcome(counter);
        failingRefundCases.failOnSaveOf(failingMoney, failingOwner);

        refundTracking.trackDueCases();

        assertThat(failingRefundCases.stateBeforeFailure()).as("실패 직전 DB에 나간 상태")
            .isEqualTo(failingMoney.name() + "/" + failingOwner.name());
        assertThat(state(ORDER_ID)).isEqualTo("CHECKING/AUTO");
        assertThat(jdbc.queryForObject("select concluded_at is null and owner_changed_at is null from payment_refund_cases"
            + " where order_id = ?", Boolean.class, ORDER_ID)).isTrue();
        assertThat(outcome(counter) - before).isZero();
        assertThat(logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList()).singleElement()
            .satisfies(event -> assertThat(event.getFormattedMessage()).contains("causeType=IllegalStateException"));
    }

    @Test
    @DisplayName("관리자 환불함 기록 저장이 flush되고 셈 등록까지 지난 뒤 commit 직전에 실패해 rollback되면 대상은 청구 확인·사람 담당 중 그대로이고 refunded/person counter는 늘지 않는다")
    void doesNotCountRolledBackPersonResolution() {
        failingRefundCases.save(RefundCase.restore(ORDER_ID, TestPgFixture.PROVIDER_ID, PROVIDER_ORDER_ID, new Money(1000),
            MoneyStatus.CHARGED, null, Owner.PERSON, RefundCase.RESULT_REVERSED, NOW, null, null,
            PaymentRecord.ProviderStatus.PENDING, "TX-1", NOW, NOW, NOW.plus(RefundCase.PROMISE_AFTER),
            NOW.plus(Duration.ofMinutes(30)), 0, null, null));
        double before = outcome("refunded/person");
        failingRefundCases.failOnSaveOf(MoneyStatus.REFUNDED, Owner.PERSON);

        assertThatThrownBy(() -> personResolution.resolve(ORDER_ID, "REFUNDED", "ops-admin"))
            .hasMessage("주입된 환불 결론 저장 실패");

        assertThat(failingRefundCases.stateBeforeFailure()).as("실패 직전 DB에 나간 상태").isEqualTo("REFUNDED/PERSON");
        assertThat(state(ORDER_ID)).isEqualTo("CHARGED/PERSON");
        assertThat(jdbc.queryForObject("select concluded_at is null and owner_closed_at is null and refund_actor is null"
            + " from payment_refund_cases where order_id = ?", Boolean.class, ORDER_ID)).isTrue();
        assertThat(outcome("refunded/person") - before).isZero();
    }

    @Test
    @DisplayName("C1: 조회 결과 반영(환불 요청함 저장)이 flush된 뒤 commit 직전에 실패해 rollback되면 돈·담당·요청 시각은 그대로이고 취소를 보내지 않으며 마지막 확인 APPLY_FAILED·원인 클래스 이름은 별도 트랜잭션으로 commit되고 apply_failed counter가 1 늘며 ERROR 한 줄(orderId·causeType만)이 남는다")
    void recordsApplyFailedInSeparateTransaction() {
        failingRefundCases.save(RefundCase.restore(ORDER_ID, TestPgFixture.PROVIDER_ID, PROVIDER_ORDER_ID, new Money(1000),
            MoneyStatus.CHECKING, null, Owner.AUTO, null, null, null, null, PaymentRecord.ProviderStatus.PENDING, null, NOW,
            NOW.plus(RefundCase.HANDOFF_AFTER), NOW.plus(RefundCase.PROMISE_AFTER), NOW, 0, null, null));
        server.addTransaction(PROVIDER_ORDER_ID, 1000, "SUCCESS");
        Counter applyFailed = meterRegistry.find(CHECK_METRIC).tag("outcome", "apply_failed").counter();
        double before = applyFailed == null ? 0 : applyFailed.count();
        failingRefundCases.failOnSaveOf(MoneyStatus.REFUND_REQUESTED, Owner.AUTO);

        refundTracking.trackDueCases();

        assertThat(failingRefundCases.stateBeforeFailure()).as("실패 직전 DB에 나간 상태").isEqualTo("REFUND_REQUESTED/AUTO");
        assertThat(state(ORDER_ID)).isEqualTo("CHECKING/AUTO");
        Map<String, Object> row = jdbc.queryForMap("select refund_requested_at, last_check_at, last_check_outcome,"
            + " last_check_cause from payment_refund_cases where order_id = ?", ORDER_ID);
        assertThat(row.get("refund_requested_at")).isNull();
        assertThat(row.get("last_check_outcome")).isEqualTo("APPLY_FAILED");
        assertThat(row.get("last_check_cause")).isEqualTo("IllegalStateException");
        assertThat(row.get("last_check_at")).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(pg.cancelCalls(PROVIDER_ORDER_ID)).isZero();
        Counter afterCounter = meterRegistry.find(CHECK_METRIC).tag("outcome", "apply_failed").counter();
        assertThat(afterCounter).isNotNull();
        assertThat(afterCounter.count() - before).isEqualTo(1.0);
        assertThat(logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList()).singleElement()
            .satisfies(event -> {
                assertThat(event.getMDCPropertyMap()).containsEntry("orderId", String.valueOf(ORDER_ID));
                assertThat(event.getFormattedMessage()).contains("causeType=IllegalStateException")
                    .doesNotContain("주입된");
                assertThat(event.getThrowableProxy()).isNull();
            });
    }

    private String state(long orderId) {
        return jdbc.queryForObject("select concat(money_status, '/', owner) from payment_refund_cases where order_id = ?",
            String.class, orderId);
    }

    // "결과/주체"는 결론 counter, "assigned/사유"는 사람 담당 counter다.
    private double outcome(String key) {
        String[] parts = key.split("/");
        Counter counter = "assigned".equals(parts[0])
            ? meterRegistry.find(ASSIGNED_METRIC).tag("reason", parts[1]).counter()
            : meterRegistry.find(OUTCOME_METRIC).tag("result", parts[0]).tag("actor", parts[1]).counter();
        return counter == null ? 0 : counter.count();
    }

    @TestConfiguration
    static class FailureInjectionConfig {
        @Bean
        @Primary
        MutablePaymentClock refundRollbackClock() {
            return new MutablePaymentClock();
        }

        @Bean
        @Primary
        FailingRefundCases failingRefundCases(PaymentRefundCasePersistenceAdapter delegate, EntityManager entityManager,
            JdbcTemplate jdbcTemplate) {
            return new FailingRefundCases(delegate, entityManager, jdbcTemplate);
        }
    }

    static class FailingRefundCases implements PaymentRefundCaseRepository {
        private final PaymentRefundCaseRepository delegate;
        private final EntityManager entityManager;
        private final JdbcTemplate jdbcTemplate;
        private final AtomicReference<String> failingState = new AtomicReference<>();
        private final AtomicReference<String> stateBeforeFailure = new AtomicReference<>();

        FailingRefundCases(PaymentRefundCaseRepository delegate, EntityManager entityManager, JdbcTemplate jdbcTemplate) {
            this.delegate = delegate;
            this.entityManager = entityManager;
            this.jdbcTemplate = jdbcTemplate;
        }

        void failOnSaveOf(MoneyStatus moneyStatus, Owner owner) {
            stateBeforeFailure.set(null);
            failingState.set(moneyStatus.name() + "/" + owner.name());
        }

        void disarm() {
            failingState.set(null);
        }

        String stateBeforeFailure() {
            return stateBeforeFailure.get();
        }

        @Override
        public RefundCase save(RefundCase refundCase) {
            RefundCase saved = delegate.save(refundCase);
            if ((refundCase.getMoneyStatus().name() + "/" + refundCase.getOwner().name()).equals(failingState.get())) {
                entityManager.flush();
                stateBeforeFailure.set(jdbcTemplate.queryForObject("select concat(money_status, '/', owner)"
                    + " from payment_refund_cases where order_id = ?", String.class, refundCase.getOrderId()));
                failingState.set(null);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("주입된 환불 결론 저장 실패");
                    }
                });
            }
            return saved;
        }

        @Override
        public Optional<RefundCase> findByOrderId(long orderId) {
            return delegate.findByOrderId(orderId);
        }

        @Override
        public boolean existsByOrderId(long orderId) {
            return delegate.existsByOrderId(orderId);
        }

        @Override
        public Optional<RefundCase> findByOrderIdForUpdate(long orderId) {
            return delegate.findByOrderIdForUpdate(orderId);
        }

        @Override
        public List<Long> findDue(Instant now, int limit) {
            return delegate.findDue(now, limit);
        }

        @Override
        public void recordCheck(long orderId, RefundCase.CheckOutcome outcome, String cause, Instant checkedAt) {
            delegate.recordCheck(orderId, outcome, cause, checkedAt);
        }
    }
}
