package com.loopers.domain.like;

public interface ProductLikeRepository {

    ProductLike save(ProductLike like);

    boolean existsByUserIdAndProductId(long userId, long productId);

    void deleteByUserIdAndProductId(long userId, long productId);

    long countByProductId(long productId);
}
