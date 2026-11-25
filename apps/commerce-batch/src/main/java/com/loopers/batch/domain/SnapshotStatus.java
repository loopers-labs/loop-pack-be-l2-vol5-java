package com.loopers.batch.domain;

/**
 * snapshot 상태 (W9 D-5). 지금 공개 중인지는 상태가 아니라 공개 pointer(ranking_publication)가 말한다.
 */
public enum SnapshotStatus {
    /** 입력만 고정됨. 점수 계산·공개 전 */
    FROZEN,
    /** 순위까지 다 매긴 완성본 */
    COMPLETED
}
