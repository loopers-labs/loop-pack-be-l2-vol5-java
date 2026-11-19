package com.loopers.domain.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyRankingPeriodTest {

    private static final LocalDate MONDAY = LocalDate.parse("2026-08-17");
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-08-24T01:00:00Z");

    @DisplayName("새 revision 은 마지막 revision + 1 만 허용한다.")
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void acceptsOnlyNextRevision(int skippedOrRepeated) {
        // arrange
        WeeklyRankingPeriod period = new WeeklyRankingPeriod(MONDAY);
        period.assignRevision(1);
        period.assignRevision(2);

        // act & assert
        assertThatThrownBy(() -> period.assignRevision(skippedOrRepeated))
            .isInstanceOf(RevisionConflictException.class);
        assertThat(period.getLastRevision()).isEqualTo(2);
    }

    @DisplayName("더 새 revision 이 공개돼 있으면 pointer 를 되돌리지 않고 snapshot 을 SUPERSEDED 로 남긴다.")
    @Test
    void doesNotRollBackPointer_whenNewerRevisionPublished() {
        // arrange
        WeeklyRankingPeriod period = new WeeklyRankingPeriod(MONDAY);
        WeeklyRankingSnapshot r1 = frozen(period, 1);
        WeeklyRankingSnapshot r2 = frozen(period, 2);
        period.publish(r2, "r2-result", NOW);

        // act
        PublishOutcome outcome = period.publish(r1, "r1-result", NOW);

        // assert
        assertThat(outcome).isEqualTo(PublishOutcome.SUPERSEDED);
        assertThat(period.getPublishedSnapshotId()).isEqualTo("weekly-20260817-r2");
        assertThat(r1.getStatus()).isEqualTo(SnapshotStatus.SUPERSEDED);
    }

    @DisplayName("공개된 revision 보다 새 snapshot 을 공개하면 pointer 가 그 snapshot 으로 바뀐다.")
    @Test
    void movesPointer_whenSnapshotIsNewer() {
        // arrange
        WeeklyRankingPeriod period = new WeeklyRankingPeriod(MONDAY);
        WeeklyRankingSnapshot r1 = frozen(period, 1);
        WeeklyRankingSnapshot r2 = frozen(period, 2);
        period.publish(r1, "r1-result", NOW);

        // act
        PublishOutcome outcome = period.publish(r2, "r2-result", NOW);

        // assert
        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        assertThat(period.getPublishedSnapshotId()).isEqualTo("weekly-20260817-r2");
        assertThat(period.getPublishedRevision()).isEqualTo(2);
        assertThat(r2.getStatus()).isEqualTo(SnapshotStatus.PUBLISHED);
        assertThat(r2.getResultChecksum()).isEqualTo("r2-result");
    }

    private static WeeklyRankingSnapshot frozen(WeeklyRankingPeriod period, int revision) {
        period.assignRevision(revision);
        return WeeklyRankingSnapshot.freeze(new WeeklyPeriod(MONDAY), revision, NOW, 4, 3, "input", 1L);
    }
}
