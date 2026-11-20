package com.loopers.domain.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklySnapshotIdTest {

    @DisplayName("weekly-YYYYMMDD-rN 을 periodStart 와 revision 으로 파싱한다.")
    @Test
    void parsesSnapshotId() {
        WeeklySnapshotId id = WeeklySnapshotId.of("weekly-20260817-r1", "2026-08-17");

        assertThat(id.value()).isEqualTo("weekly-20260817-r1");
        assertThat(id.periodStart()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(id.revision()).isEqualTo(1);
    }

    @DisplayName("형식이 다르면 입력값을 담은 IllegalArgumentException 이다.")
    @Test
    void rejectsMalformed() {
        assertThatThrownBy(() -> WeeklySnapshotId.parse("weekly-2026-08-17-r1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("weekly-2026-08-17-r1");
    }

    @DisplayName("revision 0 은 거절한다.")
    @Test
    void rejectsRevisionZero() {
        assertThatThrownBy(() -> WeeklySnapshotId.parse("weekly-20260817-r0"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("weekly-20260817-r0");
    }

    @DisplayName("snapshotId 의 날짜와 periodStart 가 다르면 두 값을 담은 IllegalArgumentException 이다.")
    @Test
    void rejectsPeriodStartMismatch() {
        assertThatThrownBy(() -> WeeklySnapshotId.of("weekly-20260817-r1", "2026-08-24"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2026-08-24")
            .hasMessageContaining("weekly-20260817-r1");
    }

    @DisplayName("periodStart 가 월요일이 아니면 거절한다.")
    @Test
    void rejectsNonMonday() {
        assertThatThrownBy(() -> WeeklySnapshotId.of("weekly-20260818-r1", "2026-08-18"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("월요일")
            .hasMessageContaining("2026-08-18");
    }

    @DisplayName("기간은 [periodStart 00:00, periodStart+7일 00:00) UTC 벽시계다.")
    @Test
    void periodBounds() {
        WeeklySnapshotId id = WeeklySnapshotId.of("weekly-20260817-r1", "2026-08-17");

        assertThat(id.periodStartUtc()).isEqualTo(LocalDateTime.of(2026, 8, 17, 0, 0));
        assertThat(id.periodEndUtc()).isEqualTo(LocalDateTime.of(2026, 8, 24, 0, 0));
    }
}
