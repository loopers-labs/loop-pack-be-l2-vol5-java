package com.loopers.application.like;

import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class LikeFacade {

    private final LikeService likeService;
    private final ProductService productService;

    public void likeProduct(Long userId, Long productId) {
        productService.getProduct(productId);
        likeService.register(userId, productId);
    }

    public void unlikeProduct(Long userId, Long productId) {
        likeService.cancel(userId, productId);
    }

    public LikeListInfo getMyLikes(Long requesterId, Long userId) {
        return LikeListInfo.from(likeService.getMyLikedProductIds(requesterId, userId));
    }
}
