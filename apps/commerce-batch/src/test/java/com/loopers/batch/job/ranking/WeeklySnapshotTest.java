package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeeklySnapshotTest {

    @DisplayName("SNP-008: snapshotId 는 기간 시작일과 revision 으로 만든다.")
    @Test
    void idFromPeriodAndRevision() {
        WeeklySnapshot snapshot = new WeeklySnapshot(LocalDate.of(2026, 8, 17), 1);

        assertThat(snapshot.id()).isEqualTo("weekly-20260817-r1");
    }

    @DisplayName("RNK-001: 기간은 월요일 00:00 UTC 부터 다음 월요일 00:00 UTC 전까지다.")
    @Test
    void periodIsMondayToNextMondayInUtc() {
        WeeklySnapshot snapshot = new WeeklySnapshot(LocalDate.of(2026, 8, 17), 1);

        assertAll(
            () -> assertThat(snapshot.start()).isEqualTo(Instant.parse("2026-08-17T00:00:00Z")),
            () -> assertThat(snapshot.end()).isEqualTo(Instant.parse("2026-08-24T00:00:00Z"))
        );
    }

    @DisplayName("RNK-001: 기간 시작일이 월요일이 아니면 만들 수 없다.")
    @Test
    void rejectsNonMonday() {
        assertThatThrownBy(() -> new WeeklySnapshot(LocalDate.of(2026, 8, 18), 1))
            .isInstanceOf(IllegalArgumentException.class);
    }


    @DisplayName("revision 은 1 부터다.")
    @Test
    void rejectsRevisionBelowOne() {
        assertThatThrownBy(() -> new WeeklySnapshot(LocalDate.of(2026, 8, 17), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
