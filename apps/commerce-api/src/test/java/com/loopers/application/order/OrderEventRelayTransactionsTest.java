package com.loopers.application.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEventRelayTransactionsTest {

    @Test
    @DisplayName("재시도 간격은 1초에서 2배씩 늘고 1분에서 멈춘다")
    void backoffDoublesUpToOneMinute() {
        assertThat(OrderEventRelayTransactions.backoff(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OrderEventRelayTransactions.backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OrderEventRelayTransactions.backoff(5)).isEqualTo(Duration.ofSeconds(32));
        assertThat(OrderEventRelayTransactions.backoff(6)).isEqualTo(Duration.ofMinutes(1));
        assertThat(OrderEventRelayTransactions.backoff(1_000)).isEqualTo(Duration.ofMinutes(1));
    }
}
