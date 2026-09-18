package com.loopers.application.like;

import com.loopers.application.user.IdentifyUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.loopers.domain.like.ProductLikeRepository;
import com.loopers.domain.like.ProductLike;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.domain.product.ProductRepository;

@Component
@Transactional
@RequiredArgsConstructor
public class CreateLikeFacade {
    private final IdentifyUser identifyUser;
    private final ProductLikeRepository likes;
    private final ProductRepository products;
    public void create(Long userId, long productId) {
        long id = identifyUser.require(userId);
        products.findById(productId).orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND)).requireActive();
        if (!likes.exists(id, productId)) { likes.save(new ProductLike(id, productId)); }
    }
}
