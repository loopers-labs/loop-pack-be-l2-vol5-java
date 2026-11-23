package com.loopers.batch.job.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankingPeriodTest {

    @DisplayName("yyyy-MM-dd 모양의 월요일로 기간을 만들고, 끝은 다음 월요일이다.")
    @Test
    void parsesMonday() {
        RankingPeriod period = RankingPeriod.parse("2026-08-17");

        assertThat(period.start()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(period.end()).isEqualTo(LocalDate.parse("2026-08-24"));
    }

    @DisplayName("JDBC에 넣을 기간 범위는 UTC 자정의 LocalDateTime이다.")
    @Test
    void rangeIsUtcMidnight() {
        RankingPeriod period = RankingPeriod.parse("2026-08-17");

        assertThat(period.startAtUtc()).isEqualTo(LocalDateTime.parse("2026-08-17T00:00:00"));
        assertThat(period.endAtUtc()).isEqualTo(LocalDateTime.parse("2026-08-24T00:00:00"));
    }

    @DisplayName("해를 넘는 주도 끝은 7일 뒤 월요일이다.")
    @Test
    void periodCrossesYearEnd() {
        RankingPeriod period = RankingPeriod.parse("2026-12-28");

        assertThat(period.end()).isEqualTo(LocalDate.parse("2027-01-04"));
        assertThat(period.endAtUtc()).isEqualTo(LocalDateTime.parse("2027-01-04T00:00:00"));
    }

    @DisplayName("기간은 UTC 자정에 바뀌므로, 한국 시간으로는 월요일 09:00에 다음 기간이 시작된다.")
    @Test
    void nextPeriodStartsAtNineInSeoul() {
        LocalDateTime startInSeoul = RankingPeriod.parse("2026-08-24").startAtUtc()
            .atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(ZoneId.of("Asia/Seoul"))
            .toLocalDateTime();

        assertThat(startInSeoul).isEqualTo(LocalDateTime.parse("2026-08-24T09:00:00"));
    }

    @DisplayName("월요일이 아니면 기간을 만들지 않는다.")
    @Test
    void rejectsNonMonday() {
        assertThatThrownBy(() -> RankingPeriod.parse("2026-08-19"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("같은 날을 다른 모양으로 쓰거나 날짜가 아니면 기간을 만들지 않는다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2026-8-17", "20260817", "2026/08/17", " 2026-08-17", "2026-08-17 ", "+2026-08-17",
        "2026-02-30", "2026-08-17T00:00"})
    void rejectsOtherShapes(String value) {
        assertThatThrownBy(() -> RankingPeriod.parse(value))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
