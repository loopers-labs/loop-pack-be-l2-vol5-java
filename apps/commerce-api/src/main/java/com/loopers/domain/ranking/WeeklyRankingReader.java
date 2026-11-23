package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * commerce-batch가 완성한 주간 랭킹을 읽는다. 랭킹 테이블에 쓰지 않는다.
 */
public interface WeeklyRankingReader {

    /**
     * 그 기간에서 가장 최근에 완성된 회차. 완성된 회차가 없으면 빈 값이다.
     */
    Optional<PublishedRevision> findPublished(LocalDate periodStart);

    /**
     * 그 회차의 순위를 계산 순위 순으로 읽는다. 공개 조건에 맞는 상품만 남긴 뒤 limit만큼 자른다.
     */
    List<RankedProduct> findRankedProducts(LocalDate periodStart, int revision, int limit);
}
