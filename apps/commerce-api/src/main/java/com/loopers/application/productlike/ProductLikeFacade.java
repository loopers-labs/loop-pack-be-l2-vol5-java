package com.loopers.application.productlike;

import com.loopers.domain.productlike.ProductLikeService;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class ProductLikeFacade {
    private final UserService userService;
    private final ProductService productService;
    private final ProductLikeService productLikeService;

    /** FR-LIKE-01 좋아요 등록. 상품이 없거나 삭제됨이면 PRODUCT_NOT_FOUND (ASM-07). 멱등 (ASM-06). */
    @Transactional
    public void like(Long requesterId, Long productId) {
        userService.getUser(requesterId);
        productService.getActive(productId);
        productLikeService.like(requesterId, productId);
    }

    /** FR-LIKE-02 좋아요 취소. 상품 존재만 묻고 삭제 여부는 묻지 않는다 (원문). 멱등 (ASM-06). */
    @Transactional
    public void unlike(Long requesterId, Long productId) {
        userService.getUser(requesterId);
        productService.get(productId);
        productLikeService.unlike(requesterId, productId);
    }
}
