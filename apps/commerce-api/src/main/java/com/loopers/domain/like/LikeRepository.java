package com.loopers.domain.like;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LikeRepository {
    long countByProductId(Long productId);

    Map<Long, Long> countByProductIds(List<Long> productIds);

    Optional<LikeModel> findByUserIdAndProductId(Long userId, Long productId);

    List<LikeModel> findAllByUserId(Long userId);

    LikeModel save(LikeModel like);

    void delete(LikeModel like);
}
