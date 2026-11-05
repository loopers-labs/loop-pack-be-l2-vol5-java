package com.loopers.application.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 실패 뒤 다시 시도하는 간격과 BLOCKED 판정(docs/week7/decisions.md D-3). 설정 기본값(1초, 5분, 24시간)으로 만든다.
 */
class OutboxRetryPolicyTest {

    private final OutboxRetryPolicy policy =
        new OutboxRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofHours(24));

    @DisplayName("백오프는 1초부터 두 배씩 늘고(1, 2, 4 … 256초) 5분에서 멈춘다. (D-3)")
    @Test
    void backoffDoublesFromOneSecondAndCapsAtFiveMinutes() {
        // act
        List<Long> seconds = new ArrayList<>();
        for (int attempt = 1; attempt <= 12; attempt++) {
            seconds.add(policy.backoffAfter(attempt).toSeconds());
        }

        // assert
        assertThat(seconds).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 300L, 300L, 300L);
    }

    @DisplayName("시도 횟수가 아주 커도 5분을 넘지 않는다. (D-3)")
    @Test
    void backoffStaysCappedForLargeAttemptCounts() {
        // act & assert
        assertThat(policy.backoffAfter(1_000)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.backoffAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
    }

    @DisplayName("시도 횟수는 1부터다 — 집으면서 1 늘린 뒤의 값을 받는다.")
    @Test
    void attemptCountStartsAtOne() {
        // act & assert
        assertThrows(IllegalArgumentException.class, () -> policy.backoffAfter(0));
    }

    @DisplayName("created_at + 24시간 직전의 실패는 다시 시도하고, 정확히 24시간이나 그 뒤의 실패는 BLOCKED로 뺀다. (D-3, D-16)")
    @Test
    void blocksExactlyAtAndAfterTwentyFourHours() {
        // arrange
        ZonedDateTime createdAt = ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime deadline = createdAt.plusHours(24);

        // act & assert
        assertThat(policy.shouldBlock(createdAt, createdAt)).isFalse();
        assertThat(policy.shouldBlock(createdAt, deadline.minusNanos(1_000))).isFalse();
        assertThat(policy.shouldBlock(createdAt, deadline)).isTrue();
        assertThat(policy.shouldBlock(createdAt, deadline.plusSeconds(1))).isTrue();
    }
}
