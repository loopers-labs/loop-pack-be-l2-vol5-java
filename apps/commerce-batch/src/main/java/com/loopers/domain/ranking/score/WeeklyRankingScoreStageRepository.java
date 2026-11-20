package com.loopers.domain.ranking.score;

import java.util.List;
import java.util.Optional;

public interface WeeklyRankingScoreStageRepository {

    Optional<WeeklyRankingScoreStage> find(String snapshotId, Long productId);

    List<WeeklyRankingScoreStage> findAll(String snapshotId);

    WeeklyRankingScoreStage save(WeeklyRankingScoreStage stage);
}
