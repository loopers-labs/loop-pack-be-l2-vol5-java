package com.loopers.config.experiment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentOriginMetricsTest {
    @Test
    @DisplayName("표본 선택 없이 원본 진입·겹침·최대 동시 수를 집계하고 실패 후 active를 반환한다")
    void countsOverlapsAndFailuresWithoutSampling() throws Throwable {
        var registry = new SimpleMeterRegistry();
        var metrics = new ExperimentOriginMetrics(registry);
        String run = "20261015T140000-measure-1700-abcdef";
        metrics.record(run, () -> metrics.record(run, () -> "value"));
        assertThatThrownBy(() -> metrics.record(run, () -> { throw new IllegalStateException("original failed"); })).isInstanceOf(IllegalStateException.class);
        assertThat(registry.get("experiment.origin.loads").tag("run", run).counter().count()).isEqualTo(3);
        assertThat(registry.get("experiment.origin.overlapping").tag("run", run).counter().count()).isEqualTo(1);
        assertThat(registry.get("experiment.origin.maximum").tag("run", run).gauge().value()).isEqualTo(2);
        assertThat(registry.get("experiment.origin.active").tag("run", run).gauge().value()).isZero();
        assertThat(registry.get("experiment.origin.errors").tag("run", run).counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("일반 요청과 검증 요청은 부하 회차의 원본 카운터에 섞지 않는다")
    void excludesRequestsWithoutExperimentRun() throws Throwable {
        var registry = new SimpleMeterRegistry();
        var metrics = new ExperimentOriginMetrics(registry);
        assertThat(metrics.record(null, () -> "ok")).isEqualTo("ok");
        assertThat(metrics.record("invalid", () -> "ok")).isEqualTo("ok");
        assertThat(registry.getMeters()).isEmpty();
    }
}
