package com.loopers.domain.ranking;

import java.time.Instant;
import java.time.LocalDate;

public record PublishedRanking(String snapshotId, LocalDate periodStart, LocalDate periodEnd,
                               int revision, Instant frozenAt, long resultCount) {
}
