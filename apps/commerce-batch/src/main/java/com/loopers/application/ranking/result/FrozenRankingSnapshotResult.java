package com.loopers.application.ranking.result;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FrozenRankingSnapshotResult(UUID snapshotId, LocalDate periodStart, LocalDate periodEnd,
                                         int revision, Instant frozenAt, long inputCount, String inputChecksum) {
}
