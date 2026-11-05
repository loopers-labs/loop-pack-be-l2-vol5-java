package com.loopers.application.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayPropertiesTest {

    @DisplayName("재시도 간격은 연속 실패마다 두 배이고 상한을 넘지 않는다.")
    @Test
    void retryDelayAfter_doublesUpToMax() {
        OutboxRelayProperties properties = new OutboxRelayProperties(true, Duration.ofSeconds(1), 20, Duration.ofSeconds(35),
            Duration.ofSeconds(1), Duration.ofSeconds(60), 3);

        assertThat(properties.retryDelayAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.retryDelayAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.retryDelayAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.retryDelayAfter(7)).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.retryDelayAfter(100)).isEqualTo(Duration.ofSeconds(60));
    }
}
