package com.loopers.ranking.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record WeeklyRanking(
    WeeklyRankingStatus status,
    LocalDate periodStart,
    LocalDate periodEnd,
    String snapshotId,
    Instant inputFixedAt,
    List<WeeklyRankingItem> items
) {
}
