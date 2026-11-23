package com.loopers.batch.job.ranking;

import java.time.Instant;
import java.time.LocalDate;

public record SnapshotInfo(String snapshotId, LocalDate periodStart, int revision, Instant frozenAt,
    int inputCount, String inputChecksum) {
}
