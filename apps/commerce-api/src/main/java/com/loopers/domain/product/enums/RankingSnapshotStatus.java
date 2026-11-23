package com.loopers.domain.product.enums;

/**
 * 주간 랭킹 snapshot 의 상태. 전이는 commerce-batch 가 조건부 update 로 지킨다 (9주차 D-182).
 * FROZEN → COMPLETED → PUBLISHED, 새 revision 공개 시 PUBLISHED → COMPLETED (D-180), FROZEN · COMPLETED → ABANDONED (D-185)
 */
public enum RankingSnapshotStatus {
    FROZEN,
    COMPLETED,
    PUBLISHED,
    ABANDONED
}
