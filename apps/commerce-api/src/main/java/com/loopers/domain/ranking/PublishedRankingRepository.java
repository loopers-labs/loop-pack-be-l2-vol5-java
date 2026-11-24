package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PublishedRankingRepository {
    Optional<PublishedRanking> findPublished(LocalDate periodStart);

    List<RankingCandidate> findCandidates(String snapshotId, long afterRank, int size);
}
