package com.loopers.domain.ranking.repository;

import com.loopers.domain.ranking.model.vo.WeeklyPurchaseScore;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface WeeklyPartialScoreRepository {
    void lockActiveOwner(UUID snapshotId, int orderPartitionId, String ownerId, long generation);

    Map<Long, Long> insertAndRead(UUID snapshotId, int orderPartitionId, List<? extends WeeklyPurchaseScore> scores);

    void recordConflict(int orderPartitionId, String ownerId, long generation, WeeklyPurchaseScore attempted, long stored);
}
