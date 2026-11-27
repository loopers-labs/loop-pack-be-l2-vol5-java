package com.loopers.infrastructure.query.pay;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.pay.query.PaymentMetricQueryDao;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentAttempt.Outcome;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.support.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
@Sql(statements = "DELETE FROM payment_attempts")
class PaymentMetricQueryDaoIntegrationTest {
    private static final Instant OBSERVED_AT = Instant.parse("2026-11-27T01:00:00.123456Z");

    @Autowired private PaymentMetricQueryDao metrics;
    @Autowired private PaymentAttemptRepository attempts;
    @Autowired private EntityManager em;

    @Test
    @DisplayName("발송한 UNKNOWN 중 응답 기한이 관찰 시각보다 앞선 주문만 센다")
    void countsOnlyOverdueUnknownOrders() {
        Instant overdue = OBSERVED_AT.minus(1, ChronoUnit.MICROS);
        insertStarted(101, Outcome.UNKNOWN, overdue);
        insertStarted(102, Outcome.UNKNOWN, OBSERVED_AT);
        insertStarted(103, Outcome.UNKNOWN, OBSERVED_AT.plus(1, ChronoUnit.MICROS));
        insertStarted(104, Outcome.PENDING, overdue);
        insertStarted(105, Outcome.SUCCESS, overdue);
        insertStarted(106, Outcome.FAILED, overdue);
        attempts.insert(newAttempt(107));

        assertThat(metrics.countOverdueUnknown(overdue)).hasValue(0L);
        assertThat(metrics.countOverdueUnknown(OBSERVED_AT)).hasValue(1L);
        assertThat(metrics.countOverdueUnknown(OBSERVED_AT.plusNanos(1))).hasValue(2L);
        assertThat(metrics.countOverdueUnknown(OBSERVED_AT.plus(1, ChronoUnit.MICROS))).hasValue(2L);
    }

    @Test
    @DisplayName("발송한 UNKNOWN의 응답 기한을 알 수 없으면 정상 0건으로 세지 않는다")
    void missingDeadlineMakesCountUnavailable() {
        attempts.insert(newAttempt(108).startDispatch());

        assertThat(metrics.countOverdueUnknown(OBSERVED_AT)).isEmpty();
    }

    private void insertStarted(long orderId, Outcome outcome, Instant deadline) {
        PaymentAttempt attempt = attempts.insert(newAttempt(orderId).startDispatch());
        attempts.recordResponseDeadline(attempt.getAttemptKey(), deadline);
        em.clear();
        if (outcome != Outcome.UNKNOWN) {
            PaymentAttempt stored = attempts.findByKey(attempt.getAttemptKey()).orElseThrow();
            attempts.save(stored.observe(stored.getProviderOrderId(), stored.getAmount(), "pg-" + orderId, outcome));
        }
    }

    private PaymentAttempt newAttempt(long orderId) {
        return PaymentAttempt.create(orderId, UUID.randomUUID().toString(), 1000L, PaymentScenario.resolve(null, null));
    }
}
