package com.loopers.outbox.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * backoff 대기 시간 (docs/week7/plan.md D-3). 난수를 고정해 리터럴로 확인한다.
 * 난수 0.5·0.75는 이진수로 정확히 표현되는 값이라 밀리초까지 리터럴로 맞는다.
 */
class OrderOutboxBackoffTest {

    private static OrderOutboxBackoff withRandom(double value) {
        return new OrderOutboxBackoff(() -> value);
    }

    @DisplayName("D-3 난수 0.5면 상한(10초 × 3^(n-1))의 절반이다: 1차 5초, 2차 15초, 3차 45초, 4차 135초.")
    @Test
    void halfOfExponentialCap() {
        // arrange
        OrderOutboxBackoff backoff = withRandom(0.5);

        // act & assert
        assertThat(backoff.delayAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(backoff.delayAfter(2)).isEqualTo(Duration.ofSeconds(15));
        assertThat(backoff.delayAfter(3)).isEqualTo(Duration.ofSeconds(45));
        assertThat(backoff.delayAfter(4)).isEqualTo(Duration.ofSeconds(135));
    }

    @DisplayName("D-3 상한은 5분이다: 5차(10초 × 81 = 810초)도 난수 0.5면 150초.")
    @Test
    void cappedAtFiveMinutes() {
        // arrange
        OrderOutboxBackoff backoff = withRandom(0.5);

        // act & assert
        assertThat(backoff.delayAfter(5)).isEqualTo(Duration.ofSeconds(150));
    }

    @DisplayName("D-3 아주 큰 실패 번호에서도 넘치지 않고 상한을 쓴다.")
    @Test
    void doesNotOverflowForLargeFailureNumber() {
        // arrange
        OrderOutboxBackoff backoff = withRandom(0.5);

        // act & assert
        assertThat(backoff.delayAfter(100)).isEqualTo(Duration.ofSeconds(150));
    }

    @DisplayName("D-3 full jitter: 난수에 비례한다 — 0이면 바로 재시도(0초), 0.75면 2차 상한 30초의 22.5초.")
    @Test
    void proportionalToRandom() {
        // act & assert
        assertThat(withRandom(0.0).delayAfter(2)).isEqualTo(Duration.ZERO);
        assertThat(withRandom(0.75).delayAfter(2)).isEqualTo(Duration.ofMillis(22_500));
    }

    @DisplayName("D-3 난수의 최댓값(1 바로 아래)에서도 상한을 넘지 않는다 — 2차는 30초 미만.")
    @Test
    void neverReachesCap() {
        // act
        Duration delay = withRandom(Math.nextDown(1.0)).delayAfter(2);

        // assert
        assertThat(delay).isLessThan(Duration.ofSeconds(30)).isGreaterThan(Duration.ofMillis(29_990));
    }

    @DisplayName("D-3 실패 번호는 1부터다 — 0 이하는 호출 버그라 거절한다.")
    @Test
    void rejectsNonPositiveFailureNumber() {
        // arrange
        OrderOutboxBackoff backoff = withRandom(0.5);

        // act & assert
        assertThatThrownBy(() -> backoff.delayAfter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
