package com.loopers.domain.like;

public interface LikeRepository {

    void save(ProductLike like);

    void delete(Long userId, Long productId);

    long countByProductId(Long productId);
}
