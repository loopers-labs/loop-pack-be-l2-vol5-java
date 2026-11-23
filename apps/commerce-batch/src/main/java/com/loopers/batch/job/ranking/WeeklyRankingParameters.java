package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.domain.RankingPeriod;
import com.loopers.batch.job.ranking.domain.SnapshotId;

import org.springframework.batch.core.JobParameters;

public record WeeklyRankingParameters(RankingPeriod period, SnapshotId snapshotId, SnapshotId sourceSnapshotId) {
    public static final String PERIOD_START = "periodStart";
    public static final String SNAPSHOT_ID = "snapshotId";
    public static final String SOURCE_SNAPSHOT_ID = "sourceSnapshotId";

    public static WeeklyRankingParameters of(String periodStart, String snapshotId) {
        return of(periodStart, snapshotId, null);
    }

    public static WeeklyRankingParameters of(String periodStart, String snapshotId, String sourceSnapshotId) {
        RankingPeriod period = RankingPeriod.parse(periodStart);
        SnapshotId target = SnapshotId.parse(snapshotId, period);
        SnapshotId source = sourceSnapshotId == null ? null : SnapshotId.parse(sourceSnapshotId, period);
        if (source != null && source.revision() >= target.revision()) {
            throw new IllegalArgumentException("sourceSnapshotId must be an earlier revision of the same week");
        }
        return new WeeklyRankingParameters(period, target, source);
    }

    public static WeeklyRankingParameters from(JobParameters parameters) {
        return of(parameters.getString(PERIOD_START), parameters.getString(SNAPSHOT_ID),
                parameters.getString(SOURCE_SNAPSHOT_ID));
    }
}
