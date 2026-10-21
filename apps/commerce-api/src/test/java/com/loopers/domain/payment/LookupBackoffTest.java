package com.loopers.domain.payment;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LookupBackoffTest {
    @ParameterizedTest(name = "실제 조회 횟수={0}, 명목 간격={1}초")
    @CsvSource({"0,5", "1,10", "2,20", "3,40", "4,60", "5,60", "12,60"})
    @DisplayName("명목 간격은 5초부터 두 배로 증가하고 60초에서 멈춘다")
    void capsExponentialDelay(int attempts, int seconds) {
        LookupBackoff backoff = new LookupBackoff(Duration.ofSeconds(5), 2, Duration.ofMinutes(1));
        assertThat(backoff.ceiling(attempts)).isEqualTo(Duration.ofSeconds(seconds));
    }
}
