package com.loopers.application.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderOutboxRelayPropertiesTest {

    private static OrderOutboxRelayProperties properties(Duration initial, double multiplier, Duration max) {
        return new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 50, initial, multiplier, max, 5);
    }

    @DisplayName("실패한 뒤 기다리는 시간은 첫 실패에 initial이고, 실패마다 배수로 늘다가 상한에서 멈춘다.")
    @Test
    void growsBackoffUpToMax() {
        // arrange
        OrderOutboxRelayProperties properties = properties(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10));

        // act & assert
        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.backoffAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.backoffAfter(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(properties.backoffAfter(5)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.backoffAfter(1_000)).as("시도 수가 아주 커도 넘치지 않고 상한이다").isEqualTo(Duration.ofSeconds(10));
    }

    @DisplayName("즉시 무한 반복이 되는 설정은 거부한다.")
    @Test
    void rejectsSettingsThatRetryWithoutWaitingLonger() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 0.5, Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Duration.ofSeconds(10), 2.0, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 0, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 50, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
