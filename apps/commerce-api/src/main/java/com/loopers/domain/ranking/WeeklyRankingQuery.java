package com.loopers.domain.ranking;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WeeklyRankingQuery {

    Optional<Published> findPublished(WeekStart week, RankingLimit limit);

    record Published(String snapshotId, LocalDateTime frozenAt, List<Item> items) {}

    record Item(
        int rank,
        long productId,
        long score,
        String productName,
        Long brandId,
        String brandName,
        long price,
        boolean soldOut
    ) {}
}
