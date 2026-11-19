package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.WeeklyRankingScoreStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WeeklyRankingScoreStageJpaRepository extends JpaRepository<WeeklyRankingScoreStage, Long> {

    Optional<WeeklyRankingScoreStage> findBySnapshotIdAndProductId(String snapshotId, Long productId);

    List<WeeklyRankingScoreStage> findBySnapshotId(String snapshotId);
}
