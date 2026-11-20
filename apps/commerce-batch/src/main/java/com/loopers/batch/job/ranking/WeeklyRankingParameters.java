package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.domain.RankingPeriod;
import com.loopers.batch.job.ranking.domain.SnapshotId;

import org.springframework.batch.core.JobParameters;

public record WeeklyRankingParameters(RankingPeriod period, SnapshotId snapshotId) {
    public static final String PERIOD_START = "periodStart";
    public static final String SNAPSHOT_ID = "snapshotId";

    public static WeeklyRankingParameters of(String periodStart, String snapshotId) {
        RankingPeriod period = RankingPeriod.parse(periodStart);
        return new WeeklyRankingParameters(period, SnapshotId.parse(snapshotId, period));
    }

    public static WeeklyRankingParameters from(JobParameters parameters) {
        return of(parameters.getString(PERIOD_START), parameters.getString(SNAPSHOT_ID));
    }
}
