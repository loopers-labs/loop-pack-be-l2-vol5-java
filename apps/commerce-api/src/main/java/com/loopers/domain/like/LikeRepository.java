package com.loopers.domain.like;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LikeRepository {
    LikeModel save(LikeModel like);

    Optional<LikeModel> findByUserIdAndProductId(Long userId, Long productId);

    Page<LikeModel> findActiveByUserId(Long userId, Pageable pageable);

    long countActiveByProductId(Long productId);

    Map<Long, Long> countActiveByProductIds(List<Long> productIds);
}
