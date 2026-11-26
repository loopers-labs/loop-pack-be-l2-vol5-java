package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.RefundOutcomeRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerRefundOutcomeRecorderTest {
    @Test
    @DisplayName("시작하면 소문자 result label 세 counter가 0으로 있고 기록한 결과만 1 늘며 주문 ID 같은 다른 label은 없다")
    void registersLowercaseResultsAndCountsOnlyRecorded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefundOutcomeRecorder recorder = new MicrometerRefundOutcomeRecorder(registry);

        assertThat(count(registry, "refunded")).isZero();
        assertThat(count(registry, "closed_no_charge")).isZero();
        assertThat(count(registry, "handed_to_human")).isZero();

        recorder.record(RefundOutcomeRecorder.Outcome.HANDED_TO_HUMAN);

        assertThat(count(registry, "handed_to_human")).isEqualTo(1.0);
        assertThat(count(registry, "refunded")).isZero();
        assertThat(count(registry, "closed_no_charge")).isZero();
        assertThat(registry.find(MicrometerRefundOutcomeRecorder.METRIC_NAME).counters())
            .allSatisfy(counter -> assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("result"));
    }

    private static double count(SimpleMeterRegistry registry, String result) {
        Counter counter = registry.find(MicrometerRefundOutcomeRecorder.METRIC_NAME).tag("result", result).counter();
        return counter == null ? -1 : counter.count();
    }
}
