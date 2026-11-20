package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.WeeklyRankingSnapshot;
import com.loopers.domain.ranking.WeeklyRankingSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class WeeklyRankingSnapshotRepositoryImpl implements WeeklyRankingSnapshotRepository {
    private final WeeklyRankingSnapshotJpaRepository jpaRepository;

    @Override
    public Optional<WeeklyRankingSnapshot> findBySnapshotId(String snapshotId) {
        return jpaRepository.findBySnapshotId(snapshotId);
    }

    @Override
    public WeeklyRankingSnapshot save(WeeklyRankingSnapshot snapshot) {
        return jpaRepository.save(snapshot);
    }
}
