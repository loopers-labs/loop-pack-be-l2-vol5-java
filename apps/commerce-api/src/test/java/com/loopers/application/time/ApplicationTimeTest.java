package com.loopers.application.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationTimeTest {

    @DisplayName("Clock 시각을 DB 정밀도인 마이크로초로 잘라 반환한다.")
    @Test
    void returnsClockTimeWithDatabasePrecision() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456789Z"), ZoneOffset.UTC);
        ApplicationTime applicationTime = new ApplicationTime(clock);

        ZonedDateTime now = applicationTime.now();

        assertThat(now).isEqualTo(ZonedDateTime.parse("2026-01-01T00:00:00.123456Z"));
    }
}
