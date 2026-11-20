package com.loopers.domain.ranking.repository;

import com.loopers.domain.ranking.model.vo.WeeklyPurchaseScore;
import java.util.List;
import java.util.UUID;

public interface WeeklyScoreRepository {
    WeeklyPurchaseScore calculate(UUID snapshotId, long productId);

    void upsert(List<? extends WeeklyPurchaseScore> scores);
}
