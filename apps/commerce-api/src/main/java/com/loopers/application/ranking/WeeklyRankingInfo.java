package com.loopers.application.ranking;

import com.loopers.domain.ranking.WeeklyRankingQueryResult.Item;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 미공개면 snapshotId·inputFixedAt 이 null 이고 items 는 빈 목록이다. inputFixedAt 은 UTC 필드다. */
public record WeeklyRankingInfo(
    Status status,
    LocalDate periodStart,
    LocalDate periodEnd,
    String snapshotId,
    LocalDateTime inputFixedAt,
    List<Item> items
) {

    public enum Status { NOT_PUBLISHED, PUBLISHED }
}
