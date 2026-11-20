package com.loopers.ranking.domain;

/**
 * snapshot 상태 (W9 D-5). commerce-batch의 SnapshotStatus와 같은 값이어야 한다 (DB의 enum 컬럼).
 */
public enum RankingSnapshotStatus {
    FROZEN,
    COMPLETED
}
