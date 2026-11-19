package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

    @DisplayName("SNP-008: 요청한 snapshotId 가 기간과 revision 으로 만든 값과 같으면 받아들인다.")
    @Test
    void acceptsMatchingId() {
        WeeklySnapshot snapshot = new WeeklySnapshot(LocalDate.of(2026, 8, 17), 1);

        assertThatCode(() -> snapshot.requireId("weekly-20260817-r1")).doesNotThrowAnyException();
    }

    @DisplayName("SNP-008: 요청한 snapshotId 가 기간과 revision 으로 만든 값과 다르면 거부한다.")
    @Test
    void rejectsMismatchedId() {
        WeeklySnapshot snapshot = new WeeklySnapshot(LocalDate.of(2026, 8, 17), 1);

        assertThatThrownBy(() -> snapshot.requireId("weekly-20260817-r2"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("SNP-006: 이미 있는 snapshot 이 같은 기간과 revision 이면 받아들인다.")
    @Test
    void acceptsSameExistingSnapshot() {
        WeeklySnapshot snapshot = new WeeklySnapshot(LocalDate.of(2026, 8, 17), 1);

        assertThatCode(() -> snapshot.requireSameAs(new WeeklySnapshot(LocalDate.of(2026, 8, 17), 1)))
            .doesNotThrowAnyException();
    }

    @DisplayName("SNP-006: 이미 있는 snapshot 이 다른 기간이면 덮지 않고 거부한다.")
    @Test
    void rejectsExistingSnapshotOfAnotherPeriod() {
        WeeklySnapshot snapshot = new WeeklySnapshot(LocalDate.of(2026, 8, 17), 1);

        assertThatThrownBy(() -> snapshot.requireSameAs(new WeeklySnapshot(LocalDate.of(2026, 8, 24), 1)))
            .isInstanceOf(IllegalStateException.class);
    }

    @DisplayName("REV-002: 그 기간의 가장 큰 revision 보다 크면 받아들인다.")
    @Test
    void acceptsRevisionAboveLatest() {
        WeeklySnapshot snapshot = new WeeklySnapshot(LocalDate.of(2026, 8, 17), 2);

        assertThatCode(() -> snapshot.requireAbove(1)).doesNotThrowAnyException();
    }

    @DisplayName("REV-002: 그 기간의 가장 큰 revision 보다 크지 않으면 거부한다.")
    @Test
    void rejectsRevisionNotAboveLatest() {
        WeeklySnapshot snapshot = new WeeklySnapshot(LocalDate.of(2026, 8, 17), 2);

        assertAll(
            () -> assertThatThrownBy(() -> snapshot.requireAbove(2)).isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> snapshot.requireAbove(3)).isInstanceOf(IllegalStateException.class)
        );
    }
}
