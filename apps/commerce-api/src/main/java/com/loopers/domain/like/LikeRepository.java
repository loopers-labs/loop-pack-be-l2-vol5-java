package com.loopers.domain.like;

public interface LikeRepository {

    boolean save(ProductLike like);

    boolean delete(Long userId, Long productId);

    void increaseCount(Long productId);

    void decreaseCount(Long productId);

    long countByProductId(Long productId);
}
