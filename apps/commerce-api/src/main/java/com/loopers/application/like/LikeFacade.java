package com.loopers.application.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class LikeFacade {

    private final UserRepository userRepository;
    private final ProductService productService;
    private final LikeService likeService;

    /**
     * @return 새로 등록되었으면 true, 이미 있었으면 false
     */
    public boolean like(Long userId, Long productId) {
        requireIdentifiedUser(userId);
        productService.getActiveProduct(productId);
        return likeService.like(userId, productId);
    }

    /**
     * 삭제된 상품이어도 기존 좋아요는 취소할 수 있으므로 상품 상태를 확인하지 않는다.
     */
    public void unlike(Long userId, Long productId) {
        requireIdentifiedUser(userId);
        likeService.unlike(userId, productId);
    }

    public List<LikeInfo> getMyLikes(Long requesterId, Long userId) {
        requireIdentifiedUser(requesterId);
        if (!requesterId.equals(userId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "[userId = " + userId + "] 좋아요 목록을 찾을 수 없습니다.");
        }

        List<Long> productIds = likeService.getLikes(requesterId).stream()
            .map(Like::getProductId)
            .toList();
        if (productIds.isEmpty()) {
            return List.of();
        }

        List<Product> products = productService.findActiveProducts(productIds);
        Map<Long, Long> likeCounts = likeService.countByProductIds(
            products.stream().map(Product::getId).toList());

        return products.stream()
            .map(product -> LikeInfo.of(product, likeCounts.getOrDefault(product.getId(), 0L)))
            .toList();
    }

    private void requireIdentifiedUser(Long userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "사용자 식별에 실패했습니다.");
        }
    }
}
