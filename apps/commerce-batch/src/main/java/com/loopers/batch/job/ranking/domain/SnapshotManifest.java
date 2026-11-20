package com.loopers.batch.job.ranking.domain;

import java.time.LocalDateTime;

public record SnapshotManifest(
        String snapshotId,
        RankingPeriod period,
        int revision,
        SnapshotStatus status,
        LocalDateTime frozenAt,
        InputSummary input) {

    public static SnapshotManifest frozen(SnapshotId id, LocalDateTime frozenAtUtc, InputSummary input) {
        return new SnapshotManifest(id.value(), id.period(), id.revision(), SnapshotStatus.FROZEN, frozenAtUtc, input);
    }

    public boolean definedAs(SnapshotId id) {
        return snapshotId.equals(id.value()) && period.equals(id.period()) && revision == id.revision();
    }
}
