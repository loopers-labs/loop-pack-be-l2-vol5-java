package com.loopers.domain.ranking;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

public interface RankingScoreRepository {

    Optional<RankingInputManifest> findSnapshot(String snapshotId);

    /** 대상 입력 행이 없으면 empty. */
    Optional<BigInteger> sumQuantity(String snapshotId, long productId);

    /** (snapshotId, productId) 별 절대 점수 upsert. 기존 점수에 더하지 않는다. */
    void upsertScores(String snapshotId, List<RankingProductScore> scores);
}
