package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 기간 공개 pointer 와 그것이 가리키는 snapshot. 연결 snapshot 이 없으면 snapshot 쪽 값은 null 이다. */
public record CurrentPublication(
    LocalDate periodStart,
    boolean pointerDeleted,
    String snapshotId,
    LocalDate snapshotPeriodStart,
    Long revision,
    String status,
    String resultChecksum,
    LocalDateTime completedAt,
    boolean snapshotDeleted
) {

    /** 같은 기간의 정상 COMPLETED snapshot 을 가리키는지. */
    public boolean pointsToCompletedSnapshot() {
        return !pointerDeleted && snapshotId != null && !snapshotDeleted
            && periodStart.equals(snapshotPeriodStart)
            && SnapshotStatus.COMPLETED.name().equals(status)
            && resultChecksum != null && completedAt != null;
    }
}
