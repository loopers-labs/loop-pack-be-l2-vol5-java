package com.loopers.domain.ranking;

import java.time.Instant;
import java.time.LocalDate;

/** 공개된 주간 랭킹 snapshot. frozenAt은 결과의 입력 기준 시점이다(그 주의 모든 구매가 도착했다는 증명은 아니다). */
public record PublishedWeeklySnapshot(LocalDate periodStart, LocalDate periodEnd, String snapshotId, int revision, Instant frozenAt) {
}
