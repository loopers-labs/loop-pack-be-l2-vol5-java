package com.loopers.domain.ranking;

import java.util.List;
import java.util.Optional;

public interface RankingSnapshotStore {
    Optional<FrozenRankingInput> find(WeeklyRankingKey key);

    List<RankingPurchase> readSourcePage(WeeklyRankingKey key, RankingPurchase after, int pageSize);

    void insertManifest(FrozenRankingInput input);

    void insertInputs(String snapshotId, List<RankingPurchase> purchases);
}
