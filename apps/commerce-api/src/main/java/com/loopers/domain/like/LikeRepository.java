package com.loopers.domain.like;

import java.util.List;
import java.util.Map;

public interface LikeRepository {
    boolean existsByUserIdAndProductId(Long userId, Long productId);

    LikeModel save(LikeModel like);

    void deleteByUserIdAndProductId(Long userId, Long productId);

    List<Long> findProductIdsByUserId(Long userId);

    long countByProductId(Long productId);

    /**
     * 상품 목록처럼 N개를 한 번에 처리할 때 N+1을 피하기 위한 일괄 조회.
     * (docs/week2/design.md 3번 섹션의 브랜드 일괄조회와 같은 원칙)
     */
    Map<Long, Long> countByProductIds(List<Long> productIds);
}
