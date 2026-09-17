package com.loopers.domain.like;

import java.util.Optional;

public interface LikeRepository {

    Optional<Like> findByUserIdAndProductId(Long userId, Long productId);

    long countByProductId(Long productId);

    Like save(Like like);

    void delete(Like like);
}
