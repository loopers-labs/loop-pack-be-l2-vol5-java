package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** ranking_snapshot 한 행의 manifest. 시각은 UTC 필드다. */
public record RankingInputManifest(
    String snapshotId,
    LocalDate periodStart,
    LocalDate periodEnd,
    long revision,
    SnapshotStatus status,
    LocalDateTime inputFixedAt,
    long inputCount,
    long productCount,
    String inputChecksum,
    String resultChecksum,
    LocalDateTime completedAt,
    boolean deleted
) {

    public static RankingInputManifest ready(
        WeeklyRankingRequest request, LocalDateTime inputFixedAt, long inputCount, long productCount, String inputChecksum
    ) {
        return new RankingInputManifest(request.snapshotId(), request.periodStart(), request.periodEnd(),
            request.revision(), SnapshotStatus.READY, inputFixedAt, inputCount, productCount, inputChecksum,
            null, null, false);
    }

    public boolean matches(WeeklyRankingRequest request) {
        return snapshotId.equals(request.snapshotId())
            && periodStart.equals(request.periodStart())
            && periodEnd.equals(request.periodEnd())
            && revision == request.revision();
    }
}
