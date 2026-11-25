package com.loopers.ranking.application.port.out;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주간 랭킹 조회 전용 약속 (W9 D-6).
 */
public interface RankingQueryPort {

    /**
     * 그 주 공개 pointer가 가리키는 snapshot. 공개본이 없으면 비어 있다.
     */
    Optional<PublishedSnapshot> findPublished(LocalDate periodStart);

    /**
     * snapshot의 순위 행 중 지금 팔 수 있는(삭제되지 않은) 상품만, 순위 순으로 limit개. 저장된 순위 번호는 그대로 둔다.
     */
    List<RankedProduct> findRankedProducts(String snapshotId, int limit);

    record PublishedSnapshot(String snapshotId, ZonedDateTime frozenAt) {}

    record RankedProduct(int rank, Long productId, long score, String name, long price, Long brandId, String brandName) {}
}
