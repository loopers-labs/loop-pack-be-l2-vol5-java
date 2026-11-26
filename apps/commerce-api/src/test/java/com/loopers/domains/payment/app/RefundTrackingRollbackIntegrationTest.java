package com.loopers.domains.payment.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.common.domain.Money;
import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.domain.PaymentRecord;
import com.loopers.domains.payment.domain.RefundCase;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;

// 결론 저장 트랜잭션이 실패하면 상태도 counter도 남지 않아야 한다(counter는 commit 뒤에만 센다).
// 실패 주입은 테스트 설정의 저장소에서 한다: 결론 상태 저장 SQL을 flush로 DB에 보낸 뒤 예외를 던지고, 서비스 트랜잭션이 끝난 뒤 새로 읽는다.
@SpringBootTest
@Import(RefundTrackingRollbackIntegrationTest.FailureInjectionConfig.class)
class RefundTrackingRollbackIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final long ORDER_ID = 3001;
    private static final String PROVIDER_ORDER_ID = String.format("LP-ORD-%010d", ORDER_ID);
    private static final String OUTCOME_METRIC = "commerce.payment.refund_outcome";

    @Autowired private RefundTrackingService refundTracking;
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

    @ParameterizedTest(name = "장면={0} → 실패한 결론={1}")
    @CsvSource({"failed, CLOSED_NO_CHARGE, closed_no_charge", "deadline, HANDED_TO_HUMAN, handed_to_human"})
    @DisplayName("결론 저장이 flush 뒤 실패해 rollback되면 대상은 TRACKING 그대로이고 결론 counter는 늘지 않으며 건별 실패만 ERROR로 남는다")
    void doesNotCountRolledBackConclusion(String scene, RefundCase.Status failingStatus, String result) {
        failingRefundCases.save(RefundCase.restore(ORDER_ID, TestPgFixture.PROVIDER_ID, PROVIDER_ORDER_ID, new Money(1000),
            RefundCase.Status.TRACKING, PaymentRecord.ProviderStatus.PENDING, null, NOW, NOW.plus(RefundCase.HANDOFF_AFTER),
            NOW.plus(RefundCase.PROMISE_AFTER), NOW, 0, null, null, null));
        server.addTransaction(PROVIDER_ORDER_ID, 1000, "deadline".equals(scene) ? "PENDING" : "FAILED");
        if ("deadline".equals(scene)) {
            clock.set(NOW.plus(Duration.ofDays(2)));
        }
        double before = outcome(result);
        failingRefundCases.failOnSaveOf(failingStatus);

        refundTracking.trackDueCases();

        assertThat(failingRefundCases.stateBeforeFailure()).as("실패 직전 DB에 나간 상태").isEqualTo(failingStatus.name());
        assertThat(jdbc.queryForObject("select status from payment_refund_cases where order_id = ?", String.class, ORDER_ID))
            .isEqualTo("TRACKING");
        assertThat(jdbc.queryForObject("select concluded_at is null from payment_refund_cases where order_id = ?", Boolean.class,
            ORDER_ID)).isTrue();
        assertThat(outcome(result) - before).isZero();
        assertThat(logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList()).singleElement()
            .satisfies(event -> assertThat(event.getFormattedMessage()).contains("causeType=IllegalStateException"));
    }

    private double outcome(String result) {
        Counter counter = meterRegistry.find(OUTCOME_METRIC).tag("result", result).counter();
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
        private final AtomicReference<RefundCase.Status> failingStatus = new AtomicReference<>();
        private final AtomicReference<String> stateBeforeFailure = new AtomicReference<>();

        FailingRefundCases(PaymentRefundCaseRepository delegate, EntityManager entityManager, JdbcTemplate jdbcTemplate) {
            this.delegate = delegate;
            this.entityManager = entityManager;
            this.jdbcTemplate = jdbcTemplate;
        }

        void failOnSaveOf(RefundCase.Status status) {
            stateBeforeFailure.set(null);
            failingStatus.set(status);
        }

        void disarm() {
            failingStatus.set(null);
        }

        String stateBeforeFailure() {
            return stateBeforeFailure.get();
        }

        @Override
        public RefundCase save(RefundCase refundCase) {
            RefundCase saved = delegate.save(refundCase);
            if (refundCase.getStatus() == failingStatus.get()) {
                entityManager.flush();
                stateBeforeFailure.set(jdbcTemplate.queryForObject("select status from payment_refund_cases where order_id = ?",
                    String.class, refundCase.getOrderId()));
                throw new IllegalStateException("주입된 환불 결론 저장 실패");
            }
            return saved;
        }

        @Override
        public Optional<RefundCase> findByOrderId(long orderId) {
            return delegate.findByOrderId(orderId);
        }

        @Override
        public Optional<RefundCase> findByOrderIdForUpdate(long orderId) {
            return delegate.findByOrderIdForUpdate(orderId);
        }

        @Override
        public List<Long> findDue(Instant now, int limit) {
            return delegate.findDue(now, limit);
        }
    }
}
