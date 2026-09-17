package com.loopers.domain.like;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductQueryResult;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Like 만 주된 상태로 변경한다. 등록 시 Product 의 상태를 읽지만 Product 를 변경하지 않는다.
 */
@RequiredArgsConstructor
@Component
public class LikeService {

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

    /**
     * 삭제된 상품이라도 삭제 전에 만든 자신의 관계는 취소할 수 있으므로
     * 상품의 삭제 여부와 관계없이 Like 를 찾아 삭제한다.
     */
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
