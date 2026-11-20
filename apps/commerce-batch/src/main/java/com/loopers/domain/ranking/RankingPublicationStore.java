package com.loopers.domain.ranking;

import java.util.List;

public interface RankingPublicationStore {
    PublicationState lockSnapshot(WeeklyRankingKey key);
    List<ProductScore> readScores(String snapshotId);
    List<RankedProduct> readResults(String snapshotId, boolean complete);
    void insertResults(CompletedRanking ranking);
    void complete(CompletedRanking ranking);
    void advancePointer(WeeklyRankingKey key);

    record PublicationState(String calculationState, Long resultCount, String checksumVersion, String checksum) {}
}
