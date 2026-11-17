package com.loopers.batch.job.ranking;

import java.time.LocalDate;

public record WeeklyRankingParameters(LocalDate periodStart, String snapshotId, int revision) {
    public static WeeklyRankingParameters parse(String periodStart, String snapshotId) {
        return new WeeklyRankingParameters(LocalDate.parse(periodStart), snapshotId, 1);
    }

    public LocalDate periodEnd() {
        return periodStart.plusDays(7);
    }
}
