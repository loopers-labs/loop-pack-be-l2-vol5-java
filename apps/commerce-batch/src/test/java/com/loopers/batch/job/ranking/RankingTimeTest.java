package com.loopers.batch.job.ranking;

import com.loopers.batch.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class RankingTimeTest {

    @DisplayName("시계의 시간대와 상관없이 UTC 날짜·시각을 마이크로초까지 돌려준다.")
    @Test
    void returnsUtcTruncatedToMicros() {
        MutableClock seoulClock = new MutableClock(Instant.parse("2026-08-24T06:00:00.123456789Z"), ZoneId.of("Asia/Seoul"));

        assertThat(new RankingTime(seoulClock).nowUtc()).isEqualTo(LocalDateTime.parse("2026-08-24T06:00:00.123456"));
    }
}
