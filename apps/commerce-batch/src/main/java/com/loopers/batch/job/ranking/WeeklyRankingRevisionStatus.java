package com.loopers.batch.job.ranking;

/**
 * 회차 상태. 점수를 고정하면 CALCULATING, 순위를 확인해 공개하면 COMPLETED, 운영자가 닫으면 CANCELLED다.
 */
public enum WeeklyRankingRevisionStatus {
    CALCULATING,
    COMPLETED,
    CANCELLED
}
