package com.loopers.domain.like;

import java.util.Optional;
import java.util.List;

public interface LikeRepository {

    Optional<Like> findByUserIdAndProductId(Long userId, Long productId);

    List<Like> findAllByUserId(Long userId);

    long countByProductId(Long productId);

    Like save(Like like);

    void delete(Like like);
}
