package com.loopers.infrastructure.like;

import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import org.springframework.stereotype.Component;

@Component
public class ProductLikeRepositoryImpl implements ProductLikeRepository {

    private final ProductLikeJpaRepository jpaRepository;

    public ProductLikeRepositoryImpl(ProductLikeJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ProductLike save(ProductLike like) {
        return jpaRepository.save(like);
    }

    @Override
    public boolean existsByUserIdAndProductId(long userId, long productId) {
        return jpaRepository.existsByUserIdAndProductId(userId, productId);
    }

    @Override
    public void deleteByUserIdAndProductId(long userId, long productId) {
        jpaRepository.deleteByUserIdAndProductId(userId, productId);
    }

    @Override
    public long countByProductId(long productId) {
        return jpaRepository.countByProductId(productId);
    }
}
