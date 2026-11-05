package com.loopers.domains.order.app;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

// R10: 실패 뒤 다음 시도까지의 간격. 갱신 뒤의 attempt_count로 계산한다(D116 [임시]: 1초×2, 최대 1분, jitter 없음).
class OutboxRetryPolicyTest {

    @ParameterizedTest(name = "attempt {0} → {1}초")
    @CsvSource({"1, 1", "2, 2", "6, 32", "7, 60", "20, 60"})
    @DisplayName("시도 횟수가 늘면 다음 시도 간격이 1초부터 두 배씩 늘고 60초에서 멈추며 큰 횟수에서도 넘치지 않는다")
    void doublesUpToOneMinute(int attemptCount, long seconds) {
        assertThat(OutboxRetryPolicy.backoff(attemptCount)).isEqualTo(Duration.ofSeconds(seconds));
    }
}
