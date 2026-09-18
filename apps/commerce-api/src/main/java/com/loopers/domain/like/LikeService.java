package com.loopers.domain.like;

import com.loopers.domain.product.ProductAvailability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LikeService {

    private final LikeRepository likeRepository;
    private final ProductAvailability productAvailability;

    public void like(Long userId, Long productId) {
        productAvailability.requireAvailable(productId);
        likeRepository.save(ProductLike.of(userId, productId));
    }

    public void unlike(Long userId, Long productId) {
        likeRepository.delete(userId, productId);
    }

    public long countOf(Long productId) {
        return likeRepository.countByProductId(productId);
    }
}
