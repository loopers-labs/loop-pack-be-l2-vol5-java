package com.loopers.domain.like;

public interface ProductLikeRepository {
    boolean exists(long userId, long productId);
    void save(ProductLike like);
    void delete(long userId, long productId);
}
