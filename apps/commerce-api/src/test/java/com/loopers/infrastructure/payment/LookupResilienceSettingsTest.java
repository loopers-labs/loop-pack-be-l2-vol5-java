package com.loopers.infrastructure.payment;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 설정의 재시도 횟수·쉬는 시간이 바뀌어도, 예산 판단에 쓰는 마지막 쉬는 시간이 같이 따라오는지 확인한다. */
class LookupResilienceSettingsTest {

    @Test
    void longestWaitFollowsAttemptsAndBackoff() {
        assertThat(settings(1).longestWait()).isEqualTo(Duration.ZERO);
        assertThat(settings(2).longestWait()).isEqualTo(Duration.ofMillis(100));
        assertThat(settings(3).longestWait()).isEqualTo(Duration.ofMillis(200));
        assertThat(settings(4).longestWait()).isEqualTo(Duration.ofMillis(400));
    }

    private static LookupResilienceSettings settings(int maxAttempts) {
        return new LookupResilienceSettings(Duration.ofSeconds(2), Duration.ofMillis(1_100),
            maxAttempts, Duration.ofMillis(100), 2.0, 4, 4, 50, Duration.ofSeconds(10), 2);
    }
}
