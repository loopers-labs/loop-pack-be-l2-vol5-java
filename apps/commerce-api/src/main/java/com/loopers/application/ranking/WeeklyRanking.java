package com.loopers.application.ranking;

import com.loopers.application.ranking.PublishedRanking.RankedProduct;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record WeeklyRanking(Status status, LocalDate periodStart, LocalDate periodEnd, String snapshotId,
        Instant frozenAt, List<RankedProduct> items) {

    public enum Status {
        PUBLISHED, NOT_PUBLISHED
    }

    static WeeklyRanking notPublished(LocalDate periodStart) {
        return new WeeklyRanking(Status.NOT_PUBLISHED, periodStart, periodStart.plusWeeks(1), null, null, List.of());
    }

    static WeeklyRanking published(LocalDate periodStart, PublishedRanking ranking) {
        return new WeeklyRanking(Status.PUBLISHED, periodStart, periodStart.plusWeeks(1),
                ranking.snapshotId(), ranking.frozenAt(), ranking.items());
    }
}
