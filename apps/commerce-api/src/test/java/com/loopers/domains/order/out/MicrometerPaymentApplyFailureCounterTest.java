package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.PaymentApplyFailureCounter.Source;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 컨텍스트의 MeterRegistry는 다른 테스트가 먼저 세면 그때 등록될 수 있어 사전 등록을 가리지 못한다. 빈 registry로 본다.
class MicrometerPaymentApplyFailureCounterTest {
    private static final List<String> SOURCES = List.of("create", "callback", "manual", "automatic");

    @Test
    @DisplayName("만들기만 해도 source 네 값(create·callback·manual·automatic)의 counter가 0으로 등록된다")
    void registersEverySourceAtZero() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        new MicrometerPaymentApplyFailureCounter(meterRegistry);

        for (String source : SOURCES) {
            Counter counter = meterRegistry.find(MicrometerPaymentApplyFailureCounter.METRIC_NAME).tag("source", source).counter();
            assertThat(counter).as("source=%s 등록", source).isNotNull();
            assertThat(counter.count()).as("source=%s", source).isZero();
        }
        assertThat(meterRegistry.find(MicrometerPaymentApplyFailureCounter.METRIC_NAME).counters()).hasSize(SOURCES.size());
    }

    @Test
    @DisplayName("manual을 한 번 세면 manual만 1이 되고 다른 source는 0이다")
    void countsOnlyGivenSource() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        MicrometerPaymentApplyFailureCounter counter = new MicrometerPaymentApplyFailureCounter(meterRegistry);

        counter.record(Source.MANUAL);

        for (String source : SOURCES) {
            double expected = source.equals("manual") ? 1 : 0;
            assertThat(meterRegistry.find(MicrometerPaymentApplyFailureCounter.METRIC_NAME).tag("source", source).counter().count())
                .as("source=%s", source).isEqualTo(expected);
        }
    }
}
