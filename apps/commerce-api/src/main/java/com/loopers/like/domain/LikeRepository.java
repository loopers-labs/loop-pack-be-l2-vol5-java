package com.loopers.like.domain;

import java.util.List;
import java.util.Optional;

public interface LikeRepository {

    Like save(Like like);

    Optional<Like> findByUserIdAndProductId(Long userId, Long productId);

    List<Like> findAllByUserId(Long userId, int page, int size);

    long countAllByUserId(Long userId);

    long countByProductId(Long productId);

    void delete(Like like);
}
