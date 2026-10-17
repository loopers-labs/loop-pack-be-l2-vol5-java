package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.like.ProductLike;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LikeRepositoryImpl implements LikeRepository {

    private final ProductLikeJpaRepository productLikeJpaRepository;
    private final ProductLikeCountJpaRepository productLikeCountJpaRepository;

    @Override
    public boolean save(ProductLike like) {
        return productLikeJpaRepository.insertIfAbsent(like.userId(), like.productId()) == 1;
    }

    @Override
    public boolean delete(Long userId, Long productId) {
        return productLikeJpaRepository.deleteRelation(userId, productId) == 1;
    }

    @Override
    public void increaseCount(Long productId) {
        productLikeCountJpaRepository.increase(productId);
    }

    @Override
    public void decreaseCount(Long productId) {
        productLikeCountJpaRepository.decrease(productId);
    }

    @Override
    public long countByProductId(Long productId) {
        return productLikeCountJpaRepository.findById(productId).map(ProductLikeCountEntity::likeCount).orElse(0L);
    }
}
