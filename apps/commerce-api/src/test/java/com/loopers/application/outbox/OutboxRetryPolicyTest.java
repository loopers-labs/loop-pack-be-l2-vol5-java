package com.loopers.application.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRetryPolicyTest {
    @Test
    void usesTheChosenBackoffAndCapsWithoutAnAttemptLimit() {
        var policy = new OutboxRetryPolicy(Duration.ofSeconds(5), Duration.ofSeconds(60));
        assertThat(IntStream.rangeClosed(1, 8).mapToObj(policy::delayFor).toList())
            .containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20),
                Duration.ofSeconds(40), Duration.ofSeconds(60), Duration.ofSeconds(60),
                Duration.ofSeconds(60), Duration.ofSeconds(60));
        assertThat(policy.delayFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void boundsVeryLargeDurationsWithoutMultiplicationOverflow() {
        Duration maximum = Duration.ofSeconds(Long.MAX_VALUE);
        var policy = new OutboxRetryPolicy(Duration.ofSeconds(Long.MAX_VALUE / 2 + 1), maximum);
        assertThat(policy.delayFor(2)).isEqualTo(maximum);
        assertThat(policy.delayFor(Integer.MAX_VALUE)).isEqualTo(maximum);
    }

    @Test
    void rejectsInvalidConfigurationAndMissingStartedAttempts() {
        assertThatThrownBy(() -> new OutboxRetryPolicy(Duration.ZERO, Duration.ofSeconds(60)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetryPolicy(Duration.ofSeconds(5), Duration.ofSeconds(4)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetryPolicy(null, Duration.ofSeconds(60)))
            .isInstanceOf(IllegalArgumentException.class);
        var policy = new OutboxRetryPolicy(Duration.ofSeconds(5), Duration.ofSeconds(60));
        assertThatThrownBy(() -> policy.delayFor(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
