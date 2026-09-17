package com.loopers.application.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.application.user.UserValidator;
import com.loopers.application.product.CustomerProductInfo;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class LikeFacade {

    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;
    private final UserValidator userValidator;

    @Transactional
    public LikeInfo add(Long userId, Long productId) {
        userValidator.validateExists(userId);
        Product product = findProduct(productId);
        if (product.getDeletedAt() != null) {
            throw new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }

        return likeRepository.findByUserIdAndProductId(userId, productId)
            .map(LikeInfo::from)
            .orElseGet(() -> LikeInfo.from(likeRepository.save(Like.create(userId, productId))));
    }

    @Transactional
    public LikeInfo cancel(Long userId, Long productId) {
        userValidator.validateExists(userId);
        return likeRepository.findByUserIdAndProductId(userId, productId)
            .map(like -> {
                likeRepository.delete(like);
                return LikeInfo.unliked(userId, productId);
            })
            .orElseGet(() -> LikeInfo.unliked(userId, productId));
    }

    @Transactional(readOnly = true)
    public long countByProductId(Long productId) {
        return likeRepository.countByProductId(productId);
    }

    @Transactional(readOnly = true)
    public List<CustomerProductInfo> getMyLikes(Long userId) {
        userValidator.validateExists(userId);
        return likeRepository.findAllByUserId(userId).stream()
            .map(Like::getProductId)
            .map(productRepository::findById)
            .flatMap(java.util.Optional::stream)
            .filter(product -> product.getDeletedAt() == null)
            .map(product -> CustomerProductInfo.from(product, likeRepository.countByProductId(product.getId())))
            .toList();
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
    }
}
