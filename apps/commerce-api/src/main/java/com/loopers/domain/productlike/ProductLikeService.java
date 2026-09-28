package com.loopers.domain.productlike;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/**
 * INV-04·INV-05 는 Model 하나로 못 지키는 같은 BC 규칙이라 여기서 지킨다 (DR-05).
 */
@RequiredArgsConstructor
@Component
public class ProductLikeService {

    private final ProductLikeRepository productLikeRepository;

    /** FR-LIKE-01: 있으면 그대로, 없으면 생성 (INV-04, ASM-06 멱등). */
    public ProductLikeModel like(Long userId, Long productId) {
        return productLikeRepository.find(userId, productId)
            .orElseGet(() -> productLikeRepository.save(new ProductLikeModel(userId, productId)));
    }

    /** FR-LIKE-02: 관계가 없으면 변화 없이 성공 (ASM-06). 물리 삭제 (DR-17). */
    public void unlike(Long userId, Long productId) {
        productLikeRepository.find(userId, productId).ifPresent(productLikeRepository::delete);
    }

    /** INV-05: 좋아요 수는 관계 개수. */
    public Map<Long, Long> countOf(Collection<Long> productIds) {
        return productLikeRepository.countByProductIds(productIds);
    }
}
