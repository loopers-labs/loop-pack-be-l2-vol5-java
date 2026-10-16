package com.loopers.domain.like;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface LikeRepository {
    void initializeCount(LikeCountModel count);

    boolean registerIfAbsent(LikeModel like);

    boolean deleteRelationship(Long userId, Long productId);

    Optional<LikeModel> find(Long userId, Long productId);

    LikeModel save(LikeModel like);

    void delete(LikeModel like);

    List<LikeModel> findByUserId(Long userId);

    Map<Long, Long> countByProductIds(List<Long> productIds);

    Set<Long> findLikedProductIds(Long userId, List<Long> productIds);
}
