package com.loopers.domain.payment;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LookupBackoffTest {
    @ParameterizedTest(name = "실제 조회 횟수={0}, 명목 간격={1}초")
    @CsvSource({"0,2", "1,4", "2,8", "3,16", "4,20", "5,20", "11,20"})
    @DisplayName("명목 간격은 2초부터 두 배로 증가하고 20초에서 멈춘다")
    void capsExponentialDelay(int attempts, int seconds) {
        LookupBackoff backoff = new LookupBackoff(Duration.ofSeconds(2), 2, Duration.ofSeconds(20));
        assertThat(backoff.ceiling(attempts)).isEqualTo(Duration.ofSeconds(seconds));
    }
}
