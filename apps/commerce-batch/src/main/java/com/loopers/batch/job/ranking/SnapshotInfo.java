package com.loopers.batch.job.ranking;

import java.time.Instant;
import java.time.LocalDate;

public record SnapshotInfo(String snapshotId, LocalDate periodStart, LocalDate periodEnd, int revision, Instant frozenAt,
    int inputCount, String inputChecksum) {
}
