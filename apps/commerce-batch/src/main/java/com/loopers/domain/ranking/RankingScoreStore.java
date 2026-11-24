package com.loopers.domain.ranking;

import java.util.List;

public interface RankingScoreStore {
    ProductScore aggregate(String snapshotId, long productId);

    void writeScores(String snapshotId, List<ProductScore> scores);
}
