package com.loopers.infrastructure.monitoring.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.loopers.application.pay.query.PaymentMetricQueryDao;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class PaymentUnknownMetricsTest {
    private static final Instant FIRST = Instant.parse("2026-11-27T01:00:00Z");
    private static final Instant SECOND = FIRST.plusSeconds(15);
    private static final Instant THIRD = SECOND.plusSeconds(15);

    private final PaymentMetricQueryDao payments = mock(PaymentMetricQueryDao.class);
    private final Clock clock = mock(Clock.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentUnknownMetrics metrics = new PaymentUnknownMetrics(payments, clock);

    @BeforeEach
    void bindMetrics() {
        metrics.bindTo(registry);
        given(clock.instant()).willReturn(FIRST, SECOND, THIRD);
    }

    @Test
    @DisplayName("첫 집계 전에는 확인 불가이고 수집해도 결제 DB를 읽지 않는다")
    void unavailableUntilFirstRefresh() {
        assertThat(count()).isNaN();
        assertThat(lastSuccess()).isNaN();
        verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("반복 수집은 마지막 집계값을 유지하고 다음 집계에서만 새 값을 읽는다")
    void readsDatabaseOncePerRefresh() {
        given(payments.countOverdueUnknown(FIRST)).willReturn(OptionalLong.of(3));
        given(payments.countOverdueUnknown(SECOND)).willReturn(OptionalLong.of(0));

        metrics.refresh();
        assertThat(count()).isEqualTo(3);
        assertThat(count()).isEqualTo(3);
        assertThat(lastSuccess()).isEqualTo(FIRST.getEpochSecond());
        verify(payments).countOverdueUnknown(FIRST);
        verifyNoMoreInteractions(payments);

        metrics.refresh();
        assertThat(count()).isZero();
        assertThat(lastSuccess()).isEqualTo(SECOND.getEpochSecond());
        verify(payments).countOverdueUnknown(SECOND);
        verifyNoMoreInteractions(payments);
    }

    @Test
    @DisplayName("DB 조회 실패 시 마지막 성공 시각을 보존하고 다음 집계에서 회복한다")
    void recoversAfterDatabaseFailure() {
        given(payments.countOverdueUnknown(FIRST)).willReturn(OptionalLong.of(0));
        given(payments.countOverdueUnknown(SECOND)).willThrow(new DataAccessResourceFailureException("DB unavailable"));
        given(payments.countOverdueUnknown(THIRD)).willReturn(OptionalLong.of(0));

        metrics.refresh();
        assertThat(count()).isZero();
        metrics.refresh();
        assertThat(count()).isNaN();
        assertThat(lastSuccess()).isEqualTo(FIRST.getEpochSecond());
        metrics.refresh();
        assertThat(count()).isZero();
        assertThat(lastSuccess()).isEqualTo(THIRD.getEpochSecond());
    }

    @Test
    @DisplayName("기한이 누락돼 전체 건수를 알 수 없으면 마지막 성공을 갱신하지 않는다")
    void unavailableCountPreservesLastSuccess() {
        given(payments.countOverdueUnknown(FIRST)).willReturn(OptionalLong.of(3));
        given(payments.countOverdueUnknown(SECOND)).willReturn(OptionalLong.empty());

        metrics.refresh();
        metrics.refresh();

        assertThat(count()).isNaN();
        assertThat(lastSuccess()).isEqualTo(FIRST.getEpochSecond());
    }

    private double count() {
        return registry.get("commerce.payment.unknown").gauge().value();
    }

    private double lastSuccess() {
        return registry.get("commerce.payment.unknown.last.success.timestamp.seconds").gauge().value();
    }
}
