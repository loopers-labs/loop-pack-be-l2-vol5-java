package com.loopers.batch.job.weeklyranking;

import com.loopers.domain.ranking.WeeklyPeriod;

import java.time.LocalDate;

/** step scope 구성 요소가 job parameter 에서 같은 snapshotId 를 다시 만든다. */
final class WeeklyRankingSnapshotIds {

    private WeeklyRankingSnapshotIds() {}

    static String of(LocalDate periodStart, Long revision) {
        return new WeeklyPeriod(periodStart).snapshotId(Math.toIntExact(revision));
    }
}
