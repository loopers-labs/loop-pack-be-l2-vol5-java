package com.loopers.domain.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyPeriodTest {

    @DisplayName("월요일이 아닌 날짜로는 주간 기간을 만들 수 없다.")
    @ParameterizedTest
    @ValueSource(strings = {"2026-08-18", "2026-08-23"})
    void rejectsNonMonday(String date) {
        assertThatThrownBy(() -> new WeeklyPeriod(LocalDate.parse(date)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("기간은 월요일 00:00Z 부터 다음 월요일 00:00Z 전까지이고, snapshotId 는 기간과 revision 으로 정해진다.")
    @Test
    void rangeAndSnapshotId() {
        // act
        WeeklyPeriod period = new WeeklyPeriod(LocalDate.parse("2026-08-17"));

        // assert
        assertThat(period.startInclusive()).isEqualTo(Instant.parse("2026-08-17T00:00:00Z"));
        assertThat(period.endExclusive()).isEqualTo(Instant.parse("2026-08-24T00:00:00Z"));
        assertThat(period.snapshotId(1)).isEqualTo("weekly-20260817-r1");
    }
}
