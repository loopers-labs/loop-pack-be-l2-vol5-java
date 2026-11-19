package com.loopers.domain.ranking;

import java.util.Optional;

public interface WeeklyRankingSnapshotRepository {

    Optional<WeeklyRankingSnapshot> findBySnapshotId(String snapshotId);

    WeeklyRankingSnapshot save(WeeklyRankingSnapshot snapshot);
}
