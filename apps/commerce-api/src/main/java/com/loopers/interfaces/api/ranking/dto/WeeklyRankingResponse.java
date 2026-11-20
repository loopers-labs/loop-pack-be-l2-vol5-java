package com.loopers.interfaces.api.ranking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loopers.application.ranking.query.WeeklyRankingCriteria;
import com.loopers.application.ranking.query.WeeklyRankingView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WeeklyRankingResponse(
    LocalDate periodStart,
    LocalDate periodEnd,
    Status status,
    @JsonInclude(JsonInclude.Include.ALWAYS) UUID snapshotId,
    @JsonInclude(JsonInclude.Include.ALWAYS) Instant frozenAt,
    List<WeeklyRankingView.Item> items
) {
    public enum Status {
        NOT_PUBLISHED,
        PUBLISHED
    }

    public static WeeklyRankingResponse published(WeeklyRankingCriteria criteria, WeeklyRankingView ranking) {
        return new WeeklyRankingResponse(criteria.periodStart(), criteria.periodEnd(), Status.PUBLISHED,
            ranking.snapshotId(), ranking.frozenAt(), ranking.items());
    }

    public static WeeklyRankingResponse notPublished(WeeklyRankingCriteria criteria) {
        return new WeeklyRankingResponse(criteria.periodStart(), criteria.periodEnd(), Status.NOT_PUBLISHED,
            null, null, List.of());
    }
}
