package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductLikeCount;
import com.loopers.domain.product.ProductLikeCountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductLikeCountRepositoryImpl implements ProductLikeCountRepository {

    private final ProductLikeCountJpaRepository productLikeCountJpaRepository;

    @Override
    public ProductLikeCount save(ProductLikeCount likeCount) {
        return productLikeCountJpaRepository.save(likeCount);
    }

    @Override
    public int increase(Long productId, ZonedDateTime updatedAt) {
        return productLikeCountJpaRepository.increase(productId, updatedAt);
    }

    @Override
    public int decrease(Long productId, ZonedDateTime updatedAt) {
        return productLikeCountJpaRepository.decrease(productId, updatedAt);
    }

    @Override
    public Optional<Long> findLikeCount(Long productId) {
        return productLikeCountJpaRepository.findLikeCount(productId);
    }
}
