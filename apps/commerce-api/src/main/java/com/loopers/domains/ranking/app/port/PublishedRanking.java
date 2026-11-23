package com.loopers.domains.ranking.app.port;

import java.time.Instant;
import java.util.List;

// 그 주에 공개된 snapshot과, 삭제 상품을 거른 뒤 calc_rank 순으로 자른 완성 랭킹 행.
public record PublishedRanking(String snapshotId, Instant frozenAt, List<RankedProduct> products) {
    public record RankedProduct(int calcRank, long productId, String name, long price, long brandId, String brandName,
        long score, int stock) { }
}
