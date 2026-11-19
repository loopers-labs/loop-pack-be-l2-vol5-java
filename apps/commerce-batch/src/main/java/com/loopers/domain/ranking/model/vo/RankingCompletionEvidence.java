package com.loopers.domain.ranking.model.vo;

import com.loopers.domain.ranking.model.SnapshotIntegrityException;

public record RankingCompletionEvidence(long expectedProductCount, long mismatchedProductCount,
                                        RankingResultFingerprint actual) {
    public RankingResultFingerprint requireComplete() {
        if (expectedProductCount < 0 || mismatchedProductCount != 0 || actual == null
            || actual.count() != expectedProductCount) {
            throw new SnapshotIntegrityException("완성본의 상품 집합·각 점수·건수가 고정 입력과 다릅니다.");
        }
        return actual;
    }
}
