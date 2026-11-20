package com.loopers.domain.ranking.repository;

import com.loopers.domain.ranking.model.vo.CompletedWeeklyRanking;
import com.loopers.domain.ranking.model.vo.PublishedRankingReference;
import com.loopers.domain.ranking.model.vo.RankingCompletionEvidence;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.domain.ranking.model.vo.RankingResultFingerprint;
import com.loopers.domain.ranking.model.RankingSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface WeeklyRankingPublicationRepository {
    Optional<PublishedRankingReference> lockPointer(RankingPeriod period);

    Optional<CompletedWeeklyRanking> findCompletion(UUID snapshotId);

    void materialize(UUID snapshotId);

    RankingCompletionEvidence inspectRankings(UUID snapshotId);

    CompletedWeeklyRanking complete(UUID snapshotId, RankingResultFingerprint result);

    void advancePointer(RankingSnapshot snapshot);
}
