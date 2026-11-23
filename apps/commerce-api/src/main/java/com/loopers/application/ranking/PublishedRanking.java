package com.loopers.application.ranking;

import java.time.Instant;
import java.util.List;

public record PublishedRanking(String snapshotId, Instant frozenAt, List<RankedProduct> items) {

    public record RankedProduct(int rank, long productId, long score, String name, long price,
            long brandId, String brandName) {
    }
}
