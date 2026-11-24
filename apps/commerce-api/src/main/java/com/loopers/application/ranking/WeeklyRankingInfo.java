package com.loopers.application.ranking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record WeeklyRankingInfo(String status, LocalDate periodStart, LocalDate periodEnd, String snapshotId,
                                int revision, Instant frozenAt, List<Item> items) {
    public WeeklyRankingInfo {
        items = List.copyOf(items);
    }

    public record Item(long productId, String productName, long score, long rank) {
    }
}
