package com.loopers.domain.product.repository;

import com.loopers.domain.product.entity.WeeklyRankingSnapshot;
import com.loopers.domain.product.vo.RankedProductView;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 주간 랭킹 읽기 전용. 쓰기는 commerce-batch 가 한다 (9주차 D-182) */
public interface WeeklyRankingRepository {

    Optional<WeeklyRankingSnapshot> findPublished(LocalDate periodStart);

    /** 삭제 상품을 거른 뒤 계산 순위 순으로 limit 개 */
    List<RankedProductView> findVisibleRanks(String snapshotId, int limit);
}
