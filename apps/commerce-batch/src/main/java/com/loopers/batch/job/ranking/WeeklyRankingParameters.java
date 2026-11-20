package com.loopers.batch.job.ranking;

import java.time.LocalDate;
import java.util.Objects;

import org.springframework.batch.core.JobParameters;

public record WeeklyRankingParameters(LocalDate periodStart, int revision, String snapshotId) {

    public static WeeklyRankingParameters from(JobParameters params) {
        Long revision = Objects.requireNonNull(params.getLong("revision"), "revision");
        return new WeeklyRankingParameters(
            params.getLocalDate("periodStart"),
            Math.toIntExact(revision),
            params.getString("snapshotId")
        );
    }

    public WeeklySnapshot snapshot() {
        return new WeeklySnapshot(periodStart, revision);
    }
}
