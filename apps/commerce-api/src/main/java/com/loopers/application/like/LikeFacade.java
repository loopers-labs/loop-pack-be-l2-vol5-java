package com.loopers.application.like;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductViewQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LikeFacade {

    private final LikeService likeService;
    private final ProductViewQuery productViewQuery;

    @Transactional
    public long like(Long userId, Long productId) {
        likeService.like(userId, productId);
        return likeService.countOf(productId);
    }

    @Transactional
    public long unlike(Long userId, Long productId) {
        likeService.unlike(userId, productId);
        return likeService.countOf(productId);
    }

    public PageWindow<ProductViewQuery.View> findLikedBy(Long userId, PageNumber page, PageSize size) {
        return productViewQuery.findLikedBy(userId, page, size);
    }
}
