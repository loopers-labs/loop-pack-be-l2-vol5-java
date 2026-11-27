package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.UnresolvedStatusSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * 지표가 "미수집·0 건·정상·집계 실패"를 구분하는지 본다 (10주차 설계 3-1-2).
 * 실제 시간을 기다리지 않도록 Clock 은 테스트가 손으로 옮기고, DB 는 스텁 repository 로 대신한다.
 */
class PaymentUnresolvedMetricsTest {

    private static final Instant T0 = Instant.parse("2026-11-20T00:00:00Z");
    private static final ZonedDateTime OLDEST = ZonedDateTime.parse("2026-11-19T10:00:00Z");

    private MeterRegistry registry;
    private PaymentAttemptRepository repository;
    private MutableClock clock;
    private PaymentUnresolvedMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        repository = Mockito.mock(PaymentAttemptRepository.class);
        clock = new MutableClock(T0);
        metrics = new PaymentUnresolvedMetrics(repository, registry, clock);
    }

    @Test
    @DisplayName("상태별 Gauge 를 세 개 등록하고, 생성만으로는 DB 를 조회하지 않는다.")
    void registersThreeStateGauges_withoutTouchingRepository() {
        assertThat(registry.find(PaymentUnresolvedMetrics.UNRESOLVED).gauges())
            .extracting(gauge -> gauge.getId().getTag("state"))
            .containsExactlyInAnyOrder("requested", "pending", "unknown");
        assertThat(registry.find(PaymentUnresolvedMetrics.OLDEST_CREATED_TIMESTAMP).gauges()).hasSize(3);
        assertThat(registry.find(PaymentUnresolvedMetrics.LAST_SUCCESS_TIMESTAMP).gauge()).isNotNull();
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("최초 집계 전에는 Gauge 시계열 7개가 모두 NaN 이고 Counter 2개는 0 이다. 0 건으로 보고하지 않는다.")
    void reportsNaN_beforeFirstRefresh() {
        for (String state : List.of("requested", "pending", "unknown")) {
            assertThat(count(state)).as("건수 state=%s", state).isNaN();
            assertThat(oldest(state)).as("가장 오래된 시각 state=%s", state).isNaN();
        }
        assertThat(lastSuccess()).isNaN();
        assertThat(refreshCount("success")).isZero();
        assertThat(refreshCount("failure")).isZero();
        assertThat(registry.find(PaymentUnresolvedMetrics.UNRESOLVED).gauges()).hasSize(3);
        assertThat(registry.find(PaymentUnresolvedMetrics.OLDEST_CREATED_TIMESTAMP).gauges()).hasSize(3);
        assertThat(registry.find(PaymentUnresolvedMetrics.REFRESH).counters()).hasSize(2);
    }

    @Test
    @DisplayName("Gauge 를 여러 번 읽어도 repository 를 호출하지 않는다. 집계는 refresh 만 한다.")
    void readingGaugesDoesNotCallRepository() {
        given(repository.summarizeUnresolved()).willReturn(summaries(0, 0, 2));
        metrics.refresh();

        for (int i = 0; i < 5; i++) {
            count("unknown");
            oldest("unknown");
            lastSuccess();
        }

        Mockito.verify(repository, Mockito.times(1)).summarizeUnresolved();
    }

    @Test
    @DisplayName("미종결이 0 건이면 건수는 0 이고 가장 오래된 시각은 NaN 이다.")
    void reportsZeroCountAndNoTimestamp_whenNothingUnresolved() {
        given(repository.summarizeUnresolved()).willReturn(summaries(0, 0, 0));

        metrics.refresh();

        assertThat(count("requested")).isZero();
        assertThat(count("pending")).isZero();
        assertThat(count("unknown")).isZero();
        assertThat(oldest("unknown")).isNaN();
        assertThat(lastSuccess()).isEqualTo(T0.toEpochMilli() / 1_000d);
    }

    @Test
    @DisplayName("UNKNOWN 이 생기면 건수와 가장 오래된 시각이 갱신된다.")
    void updatesGauges_whenUnknownAppears() {
        given(repository.summarizeUnresolved()).willReturn(summaries(1, 0, 3));

        metrics.refresh();

        assertThat(count("unknown")).isEqualTo(3d);
        assertThat(count("requested")).isEqualTo(1d);
        assertThat(oldest("unknown")).isEqualTo(OLDEST.toInstant().toEpochMilli() / 1_000d);
        assertThat(refreshCount("success")).isEqualTo(1d);
    }

    @Test
    @DisplayName("결제가 종결되면 건수가 줄고 가장 오래된 시각도 사라진다.")
    void decreasesGauges_whenSettled() {
        given(repository.summarizeUnresolved()).willReturn(summaries(0, 0, 2));
        metrics.refresh();
        assertThat(count("unknown")).isEqualTo(2d);

        given(repository.summarizeUnresolved()).willReturn(summaries(0, 0, 0));
        clock.advanceSeconds(10);
        metrics.refresh();

        assertThat(count("unknown")).isZero();
        assertThat(oldest("unknown")).isNaN();
        assertThat(lastSuccess()).isEqualTo(T0.plusSeconds(10).toEpochMilli() / 1_000d);
    }

    @Test
    @DisplayName("집계가 실패하면 마지막 성공 값과 마지막 성공 시각을 유지하고 실패만 센다. 0 으로 쓰지 않는다.")
    void keepsLastSuccessfulSnapshot_whenRefreshFails() {
        given(repository.summarizeUnresolved()).willReturn(summaries(0, 0, 3));
        metrics.refresh();
        double lastSuccessAfterFirst = lastSuccess();

        willThrow(new IllegalStateException("db down")).given(repository).summarizeUnresolved();
        clock.advanceSeconds(30);
        metrics.refresh();

        assertThat(count("unknown")).isEqualTo(3d);
        assertThat(oldest("unknown")).isEqualTo(OLDEST.toInstant().toEpochMilli() / 1_000d);
        assertThat(lastSuccess()).isEqualTo(lastSuccessAfterFirst);
        assertThat(refreshCount("failure")).isEqualTo(1d);
        assertThat(refreshCount("success")).isEqualTo(1d);
    }

    @Test
    @DisplayName("조회가 회복되면 스냅샷과 마지막 성공 시각이 다시 갱신된다.")
    void resumesUpdating_whenRepositoryRecovers() {
        willThrow(new IllegalStateException("db down")).given(repository).summarizeUnresolved();
        metrics.refresh();
        assertThat(count("unknown")).isNaN();
        assertThat(lastSuccess()).isNaN();

        Mockito.reset(repository);
        given(repository.summarizeUnresolved()).willReturn(summaries(0, 1, 0));
        clock.advanceSeconds(10);
        metrics.refresh();

        assertThat(count("pending")).isEqualTo(1d);
        assertThat(lastSuccess()).isEqualTo(T0.plusSeconds(10).toEpochMilli() / 1_000d);
        assertThat(refreshCount("failure")).isEqualTo(1d);
    }

    private List<UnresolvedStatusSummary> summaries(long requested, long pending, long unknown) {
        return List.of(
            summary(PaymentAttempt.Status.REQUESTED, requested),
            summary(PaymentAttempt.Status.PENDING, pending),
            summary(PaymentAttempt.Status.UNKNOWN, unknown));
    }

    private UnresolvedStatusSummary summary(PaymentAttempt.Status status, long count) {
        return new UnresolvedStatusSummary(status, count, count == 0 ? null : OLDEST);
    }

    private double count(String state) {
        return gauge(PaymentUnresolvedMetrics.UNRESOLVED, state).value();
    }

    private double oldest(String state) {
        return gauge(PaymentUnresolvedMetrics.OLDEST_CREATED_TIMESTAMP, state).value();
    }

    private double lastSuccess() {
        return registry.get(PaymentUnresolvedMetrics.LAST_SUCCESS_TIMESTAMP).gauge().value();
    }

    private double refreshCount(String result) {
        return registry.get(PaymentUnresolvedMetrics.REFRESH).tag("result", result).counter().count();
    }

    private Gauge gauge(String name, String state) {
        return registry.get(name).tag("state", state).gauge();
    }

    /** 실제 시간을 기다리지 않고 집계 시각을 옮기기 위한 시계. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
