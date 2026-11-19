package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.WeeklyRankingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WeeklyRankingSnapshotJpaRepository extends JpaRepository<WeeklyRankingSnapshot, Long> {
    Optional<WeeklyRankingSnapshot> findBySnapshotId(String snapshotId);
}
