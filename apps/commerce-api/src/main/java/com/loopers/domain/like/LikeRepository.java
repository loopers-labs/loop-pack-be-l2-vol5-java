package com.loopers.domain.like;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LikeRepository {

    Optional<Like> findByUserIdAndProductId(Long userId, Long productId);

    List<Like> findAllByUserId(Long userId);

    long countByProductId(Long productId);

    Map<Long, Long> countByProductIds(List<Long> productIds);

    Like save(Like like);

    void delete(Like like);
}
