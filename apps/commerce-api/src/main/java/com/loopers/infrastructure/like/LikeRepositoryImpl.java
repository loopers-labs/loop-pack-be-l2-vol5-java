package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.like.ProductLike;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LikeRepositoryImpl implements LikeRepository {

    private final ProductLikeJpaRepository productLikeJpaRepository;

    @Override
    public void save(ProductLike like) {
        productLikeJpaRepository.insertIfAbsent(like.userId(), like.productId());
    }

    @Override
    public void delete(Long userId, Long productId) {
        productLikeJpaRepository.deleteByUserIdAndProductId(userId, productId);
    }

    @Override
    public long countByProductId(Long productId) {
        return productLikeJpaRepository.countByProductId(productId);
    }
}
