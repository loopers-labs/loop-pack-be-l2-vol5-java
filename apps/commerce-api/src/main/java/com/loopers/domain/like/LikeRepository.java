package com.loopers.domain.like;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface LikeRepository {
    long countByProductId(Long productId);

    Map<Long, Long> countByProductIds(Collection<Long> productIds);

    boolean exists(Long userId, Long productId);

    Optional<Like> find(Long userId, Long productId);

    Like save(Like like);

    void delete(Like like);
}
