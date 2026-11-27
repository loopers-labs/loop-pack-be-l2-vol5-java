package com.loopers.application.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMetricsTest {

    @DisplayName("생성 호출 결과를 세는 숫자는 아직 한 번도 일어나지 않은 결과도 0 으로 내놓는다. 그래야 처음 일어난 일이 0 에서 오른 것으로 보인다")
    @Test
    void startsCreateCountersAtZero() {
        MeterRegistry registry = new SimpleMeterRegistry();

        new PaymentMetrics(registry);

        assertThat(registry.find("payment.create").counters())
            .extracting(counter -> counter.getId().getTag("result"))
            .containsExactlyInAnyOrder("accepted", "rejected", "not_sent", "unknown");
        assertThat(registry.find("payment.create").counters()).extracting(Counter::count).containsOnly(0.0);
    }
}
