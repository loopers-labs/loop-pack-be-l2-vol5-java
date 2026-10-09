package com.loopers.domain.like;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LikeRepository {
    boolean registerIfAbsent(LikeModel like);

    void deleteRelationship(Long userId, Long productId);

    Optional<LikeModel> find(Long userId, Long productId);

    LikeModel save(LikeModel like);

    void delete(LikeModel like);

    List<LikeModel> findByUserId(Long userId);

    Map<Long, Long> countByProductIds(List<Long> productIds);
}
