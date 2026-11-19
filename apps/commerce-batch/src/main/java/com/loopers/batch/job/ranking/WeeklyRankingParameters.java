package com.loopers.batch.job.ranking;

import java.time.LocalDate;

import org.springframework.batch.core.JobParameters;

public record WeeklyRankingParameters(LocalDate periodStart, int revision, String snapshotId) {

    public static WeeklyRankingParameters from(JobParameters params) {
        return new WeeklyRankingParameters(
            params.getLocalDate("periodStart"),
            Math.toIntExact(params.getLong("revision")),
            params.getString("snapshotId")
        );
    }

    public WeeklySnapshot snapshot() {
        return new WeeklySnapshot(periodStart, revision);
    }
}
