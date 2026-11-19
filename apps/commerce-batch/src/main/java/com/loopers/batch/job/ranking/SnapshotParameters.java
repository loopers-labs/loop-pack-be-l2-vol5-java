package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobParameters;

final class SnapshotParameters {

    private SnapshotParameters() {
    }

    static WeeklySnapshot snapshot(JobParameters params) {
        return new WeeklySnapshot(params.getLocalDate("periodStart"), Math.toIntExact(params.getLong("revision")));
    }

    static String requestedId(JobParameters params) {
        return params.getString("snapshotId");
    }
}
