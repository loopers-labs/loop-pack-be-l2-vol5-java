package com.loopers.domain.like;

import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;
    private final ProductService productService;

    /**
     * 없거나 삭제된 상품에는 등록하지 않는다(LIK-01, 확인은 ProductService). 이미 있는 관계면 그대로 둔다(LIK-02, 멱등).
     */
    @Transactional
    public void like(Long userId, Long productId) {
        productService.getActiveProduct(productId);
        if (likeRepository.find(userId, productId).isPresent()) {
            return;
        }
        likeRepository.save(new Like(userId, productId));
    }

    /** 상품의 상태를 보지 않고 요청자 자신의 관계만 찾는다. 없으면 이미 원하는 상태다 (LIK-03, 멱등). */
    @Transactional
    public void unlike(Long userId, Long productId) {
        likeRepository.find(userId, productId).ifPresent(likeRepository::delete);
    }

    @Transactional(readOnly = true)
    public Page<LikedProduct> getLikedProducts(Long userId, Pageable pageable) {
        return likeRepository.findLikedProducts(userId, pageable);
    }

    /** 좋아요 수는 관계에서 센다 (설계 2.3). */
    @Transactional(readOnly = true)
    public long countLikes(Long productId) {
        return likeRepository.countByProductId(productId);
    }
}
