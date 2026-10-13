package com.loopers.domain.productlike;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/**
 * INV-04·INV-05 는 Model 하나로 못 지키는 같은 BC 규칙이라 여기서 지킨다 (DR-05).
 * 좋아요 수(TB-08)는 이 Service 만 바꾼다 (DR-36).
 */
@RequiredArgsConstructor
@Component
public class ProductLikeService {

    private final ProductLikeRepository productLikeRepository;

    /**
     * FR-LIKE-01: 있으면 그대로, 없으면 생성 (INV-04, ASM-06 멱등). 새로 만들었을 때만 좋아요 수 +1 (INV-05).
     * 같은 쌍의 동시 등록은 고유 제약 위반으로 한쪽 트랜잭션이 롤백되어 +1 도 함께 취소된다.
     */
    public ProductLikeModel like(Long userId, Long productId) {
        return productLikeRepository.find(userId, productId).orElseGet(() -> {
            ProductLikeModel like = productLikeRepository.save(new ProductLikeModel(userId, productId));
            productLikeRepository.increaseLikeCount(productId);
            return like;
        });
    }

    /**
     * FR-LIKE-02: 관계가 없으면 변화 없이 성공 (ASM-06). 물리 삭제 (DR-17).
     * 실제로 지웠을 때만 좋아요 수 -1 (INV-05). 같은 쌍의 동시 취소에서도 지운 쪽만 줄인다.
     */
    public void unlike(Long userId, Long productId) {
        if (productLikeRepository.delete(userId, productId) == 1) {
            productLikeRepository.decreaseLikeCount(productId);
        }
    }

    /** FR-ADMIN-PRODUCT-02: 상품 생성과 같은 트랜잭션에서 좋아요 수 0 인 행을 만든다. */
    public void createStat(Long productId, Long brandId) {
        productLikeRepository.saveStat(new ProductStatModel(productId, brandId));
    }

    /** FR-ADMIN-PRODUCT-05: 상품 삭제와 같은 트랜잭션에서 행의 삭제를 반영한다. 좋아요 수는 그대로다 (ASM-07). */
    public void deleteStat(Long productId) {
        productLikeRepository.deleteStat(productId);
    }

    /** FR-ADMIN-BRAND-05: 소속 상품 삭제와 같은 트랜잭션에서 그 행들의 삭제를 반영한다. */
    public void deleteStatsOfBrand(Long brandId) {
        productLikeRepository.deleteStatsOfBrand(brandId);
    }

    /** INV-05: 좋아요 수. 행이 없는 상품 ID 는 결과에 없다. */
    public Map<Long, Long> countOf(Collection<Long> productIds) {
        return productLikeRepository.findLikeCounts(productIds);
    }
}
