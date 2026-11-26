package com.loopers.domains.payment.in;

import com.loopers.domains.payment.app.port.RefundCaseCounter;
import com.loopers.domains.payment.app.port.RefundCaseCounter.Snapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 포트만 fake로 두고 실제 MeterRegistry(Simple)에 등록된 gauge 값을 본다(10주차 5b 계획 4절, S7).
// 이름·label 값은 커밋된 알림 규칙·대시보드(102c864)가 쓰는 것으로 고정한다.
class RefundCaseMetricsTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CASES = "commerce.payment.refund_cases";
    private static final String PERSON_OPEN = "commerce.payment.refund_person_open";
    private static final String OLDEST_AGE = "commerce.payment.refund_requested_oldest_age_seconds";
    private static final List<String> LABELS = List.of("checking", "charged", "refund_requested", "refund_unknown", "refunded",
        "no_charge");

    @Test
    @DisplayName("첫 갱신 전에는 소문자 money_status label 6개 gauge와 사람 담당 중·요청 정체 gauge가 모두 있고 값은 0이 아닌 NaN이다")
    void registersGaugesAsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RefundCaseMetrics(() -> new Snapshot(Map.of(), 0, Optional.empty()), registry, CLOCK);

        assertThat(registry.find(CASES).gauges())
            .extracting(gauge -> gauge.getId().getTag("money_status"))
            .containsExactlyInAnyOrderElementsOf(LABELS);
        assertThat(registry.find(CASES).gauges())
            .allSatisfy(gauge -> assertThat(gauge.getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactly("money_status"));
        LABELS.forEach(label -> assertThat(cases(registry, label)).as(label).isNaN());
        assertThat(gauge(registry, PERSON_OPEN)).isNaN();
        assertThat(gauge(registry, OLDEST_AGE)).isNaN();
    }

    @Test
    @DisplayName("갱신하면 돈의 상태별 수를 소문자 label에 넣고 없는 상태는 0, 사람 담당 중 수, 가장 오래된 환불 요청의 경과 초를 넣는다")
    void refreshesCountsPersonOpenAndOldestAge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefundCaseMetrics metrics = new RefundCaseMetrics(() -> new Snapshot(
            Map.of("CHECKING", 3L, "REFUND_UNKNOWN", 2L, "CHARGED", 1L, "REFUND_REQUESTED", 4L), 5,
            Optional.of(NOW.minusSeconds(725))), registry, CLOCK);

        metrics.refresh();

        assertThat(cases(registry, "checking")).isEqualTo(3.0);
        assertThat(cases(registry, "refund_unknown")).isEqualTo(2.0);
        assertThat(cases(registry, "charged")).isEqualTo(1.0);
        assertThat(cases(registry, "refund_requested")).isEqualTo(4.0);
        assertThat(cases(registry, "refunded")).isZero();
        assertThat(cases(registry, "no_charge")).isZero();
        assertThat(gauge(registry, PERSON_OPEN)).isEqualTo(5.0);
        assertThat(gauge(registry, OLDEST_AGE)).isEqualTo(725.0);
    }

    @Test
    @DisplayName("환불 요청함 대상이 없으면 요청 정체 경과는 0이다")
    void oldestAgeIsZeroWithoutRequests() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefundCaseMetrics metrics = new RefundCaseMetrics(() -> new Snapshot(Map.of("CHECKING", 1L), 0, Optional.empty()),
            registry, CLOCK);

        metrics.refresh();

        assertThat(gauge(registry, OLDEST_AGE)).isZero();
        assertThat(gauge(registry, PERSON_OPEN)).isZero();
    }

    @Test
    @DisplayName("갱신 중 조회가 실패하면 예외를 밖으로 내지 않고 세 gauge 모두 마지막 값을 유지한다")
    void keepsLastValuesWhenRefreshFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicBoolean failing = new AtomicBoolean();
        RefundCaseCounter counter = () -> {
            if (failing.get()) {
                throw new IllegalStateException("주입된 집계 실패");
            }
            return new Snapshot(Map.of("CHARGED", 4L), 4, Optional.of(NOW.minusSeconds(30)));
        };
        RefundCaseMetrics metrics = new RefundCaseMetrics(counter, registry, CLOCK);
        metrics.refresh();
        failing.set(true);

        assertThatCode(metrics::refresh).doesNotThrowAnyException();

        assertThat(cases(registry, "charged")).isEqualTo(4.0);
        assertThat(cases(registry, "checking")).isZero();
        assertThat(gauge(registry, PERSON_OPEN)).isEqualTo(4.0);
        assertThat(gauge(registry, OLDEST_AGE)).isEqualTo(30.0);
    }

    private static double cases(SimpleMeterRegistry registry, String moneyStatus) {
        Gauge gauge = registry.find(CASES).tag("money_status", moneyStatus).gauge();
        return gauge == null ? -1 : gauge.value();
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        Gauge gauge = registry.find(name).gauge();
        return gauge == null ? -1 : gauge.value();
    }
}
