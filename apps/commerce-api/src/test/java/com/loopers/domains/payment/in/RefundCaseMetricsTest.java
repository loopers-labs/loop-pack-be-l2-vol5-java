package com.loopers.domains.payment.in;

import com.loopers.domains.payment.app.port.RefundCaseCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 포트만 fake로 두고 실제 MeterRegistry(Simple)에 등록된 gauge 값을 본다. label 값은 알림 규칙이 쓰는 소문자로 고정한다.
class RefundCaseMetricsTest {
    private static final List<String> LABELS = List.of("tracking", "refund_requested", "refund_unknown", "refunded",
        "closed_no_charge", "handed_to_human");

    @Test
    @DisplayName("첫 갱신 전에는 소문자 status label 6개 gauge가 모두 있고 값은 0이 아닌 NaN이다")
    void registersSixLowercaseStatusesAsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RefundCaseMetrics(Map::of, registry);

        assertThat(registry.find(RefundCaseMetrics.METRIC_NAME).gauges())
            .extracting(gauge -> gauge.getId().getTag("status"))
            .containsExactlyInAnyOrderElementsOf(LABELS);
        LABELS.forEach(label -> assertThat(value(registry, label)).as(label).isNaN());
    }

    @Test
    @DisplayName("갱신하면 상태별 대상 수를 소문자 label에 넣고 없는 상태는 0으로 둔다")
    void refreshesCountsByStatus() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefundCaseMetrics metrics = new RefundCaseMetrics(
            () -> Map.of("TRACKING", 3L, "REFUND_UNKNOWN", 2L, "HANDED_TO_HUMAN", 1L), registry);

        metrics.refresh();

        assertThat(value(registry, "tracking")).isEqualTo(3.0);
        assertThat(value(registry, "refund_unknown")).isEqualTo(2.0);
        assertThat(value(registry, "handed_to_human")).isEqualTo(1.0);
        assertThat(value(registry, "refund_requested")).isZero();
        assertThat(value(registry, "refunded")).isZero();
        assertThat(value(registry, "closed_no_charge")).isZero();
    }

    @Test
    @DisplayName("갱신 중 조회가 실패하면 예외를 밖으로 내지 않고 마지막 값을 유지한다")
    void keepsLastValuesWhenRefreshFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicBoolean failing = new AtomicBoolean();
        RefundCaseCounter counter = () -> {
            if (failing.get()) {
                throw new IllegalStateException("주입된 집계 실패");
            }
            return Map.of("HANDED_TO_HUMAN", 4L);
        };
        RefundCaseMetrics metrics = new RefundCaseMetrics(counter, registry);
        metrics.refresh();
        failing.set(true);

        assertThatCode(metrics::refresh).doesNotThrowAnyException();

        assertThat(value(registry, "handed_to_human")).isEqualTo(4.0);
        assertThat(value(registry, "tracking")).isZero();
    }

    private static double value(SimpleMeterRegistry registry, String status) {
        Gauge gauge = registry.find(RefundCaseMetrics.METRIC_NAME).tag("status", status).gauge();
        return gauge == null ? -1 : gauge.value();
    }
}
