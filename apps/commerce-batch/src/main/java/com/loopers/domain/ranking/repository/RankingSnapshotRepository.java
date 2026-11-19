package com.loopers.domain.ranking.repository;

import com.loopers.domain.ranking.model.vo.InputFingerprint;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.domain.ranking.model.RankingSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface RankingSnapshotRepository {
    Optional<RankingSnapshot> findById(UUID snapshotId);

    void reserve(UUID snapshotId, RankingPeriod period, int revision);

    void copyInputs(UUID snapshotId, RankingPeriod period);

    InputFingerprint fingerprint(UUID snapshotId, RankingPeriod period);

    RankingSnapshot complete(UUID snapshotId, InputFingerprint inputs);
}
