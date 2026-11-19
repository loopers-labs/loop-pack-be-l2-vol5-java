package com.loopers.application.ranking.query;

import com.loopers.application.mall.query.BrandSummaryView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WeeklyRankingView(UUID snapshotId, Instant frozenAt, List<Item> items) {
    public record Item(long productId, String name, long price, BrandSummaryView brand, long likeCount, long score, long rank) {}
}
