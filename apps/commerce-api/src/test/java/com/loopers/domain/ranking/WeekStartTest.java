package com.loopers.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeekStartTest {

    @DisplayName("QRY-002, RNK-001: 월요일이면 주의 시작이고, 끝은 다음 월요일이다.")
    @Test
    void mondayStartsWeek() {
        WeekStart week = WeekStart.of("2026-08-17");

        assertAll(
            () -> assertThat(week.value()).isEqualTo(LocalDate.of(2026, 8, 17)),
            () -> assertThat(week.end()).isEqualTo(LocalDate.of(2026, 8, 24))
        );
    }

    @DisplayName("QRY-002: 월요일이 아니면 거부한다.")
    @Test
    void rejectsNonMonday() {
        assertThatThrownBy(() -> WeekStart.of("2026-08-18")).isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("QRY-002: yyyy-MM-dd 형식이 아니면 거부한다.")
    @Test
    void rejectsOtherFormat() {
        assertThatThrownBy(() -> WeekStart.of("2026/08/17")).isInstanceOf(IllegalArgumentException.class);
    }
}
