package com.loopers.infrastructure.ranking.score;

import com.loopers.domain.ranking.score.WeeklyRankingScoreStage;
import com.loopers.domain.ranking.score.WeeklyRankingScoreStageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class WeeklyRankingScoreStageRepositoryImpl implements WeeklyRankingScoreStageRepository {
    private final WeeklyRankingScoreStageJpaRepository jpaRepository;

    @Override
    public Optional<WeeklyRankingScoreStage> find(String snapshotId, Long productId) {
        return jpaRepository.findBySnapshotIdAndProductId(snapshotId, productId);
    }

    @Override
    public List<WeeklyRankingScoreStage> findAll(String snapshotId) {
        return jpaRepository.findBySnapshotId(snapshotId);
    }

    @Override
    public WeeklyRankingScoreStage save(WeeklyRankingScoreStage stage) {
        return jpaRepository.save(stage);
    }
}
