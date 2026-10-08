package com.loopers.application.like;

import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
@RequiredArgsConstructor
public class CreateLikeFacade {
    private final IdentifyUser identifyUser;
    private final ProductLikeRepository likes;
    private final ProductRepository products;

    public void create(Long userId, long productId) {
        long id = identifyUser.require(userId);
        requireActiveProduct(productId);
        likes.registerIfAbsent(new ProductLike(id, productId));
    }

    private void requireActiveProduct(long productId) {
        products.findById(productId)
                .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND))
                .requireActive();
    }
}
