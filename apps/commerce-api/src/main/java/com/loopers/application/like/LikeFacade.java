package com.loopers.application.like;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductQueryResult;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Like API 유스케이스의 처리 순서와 트랜잭션 경계를 담당한다. */
@RequiredArgsConstructor
@Component
public class LikeFacade {

    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;
    private final ProductQueryRepository productQueryRepository;

    @Transactional
    public LikeModel like(Long userId, Long productId) {
        productRepository.findActive(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));

        if (likeRepository.exists(userId, productId)) {
            throw new CoreException(ErrorType.LIKE_ALREADY_EXISTS);
        }
        return likeRepository.save(LikeModel.of(userId, productId));
    }

    /** 삭제된 상품이라도 삭제 전에 만든 자신의 좋아요 관계는 취소할 수 있다. */
    @Transactional
    public void cancel(Long userId, Long productId) {
        LikeModel like = likeRepository.find(userId, productId)
            .orElseThrow(() -> new CoreException(ErrorType.LIKE_NOT_FOUND));
        likeRepository.delete(like);
    }

    @Transactional(readOnly = true)
    public PageResult<ProductQueryResult> getMyLikedProducts(Long userId, PageCommand page, ListSort sort) {
        return productQueryRepository.findLikedPage(userId, page, sort);
    }
}
