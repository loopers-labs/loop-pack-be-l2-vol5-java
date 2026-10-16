package com.loopers.application.like;

import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserErrorCode;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@RequiredArgsConstructor
@Component
public class LikeFacade {
    private final LikeService likeService;
    private final ProductService productService;

    /**
     * 없거나 삭제된 상품에는 등록하지 않음 (LIK-01). 상품과 좋아요 두 도메인을 다루므로 트랜잭션을 여기서 엶 (설계 4.4).
     * 관계가 실제로 생겼을 때만 좋아요 수를 늘리며, 좋아요 수 행의 잠금이 짧도록 갱신을 마지막 쓰기로 둠 (4주차 설계 3.2)
     */
    @Transactional
    public LikeInfo like(Long userId, Long productId) {
        productService.getActiveProduct(productId);
        if (likeService.like(userId, productId)) {
            productService.increaseLikeCount(productId);
        }
        return new LikeInfo(productId, productService.getLikeCount(productId));
    }

    /** 경로의 사용자가 요청자 본인이 아니면, 그 사용자의 존재를 드러내지 않도록 없는 대상으로 응답한다 (설계 6.1). */
    public Page<LikedProductInfo> getMyLikes(Long requesterId, Long userId, Pageable pageable) {
        if (!Objects.equals(requesterId, userId)) {
            throw new CoreException(UserErrorCode.USER_NOT_FOUND);
        }
        return likeService.getLikedProducts(userId, pageable).map(LikedProductInfo::from);
    }

    /** 관계를 실제로 지웠을 때만 좋아요 수를 줄임. 상품이 삭제되었어도 같다 (LIK-03, 4주차 설계 3.2) */
    @Transactional
    public LikeInfo unlike(Long userId, Long productId) {
        if (likeService.unlike(userId, productId)) {
            productService.decreaseLikeCount(productId);
        }
        return new LikeInfo(productId, productService.getLikeCount(productId));
    }
}
