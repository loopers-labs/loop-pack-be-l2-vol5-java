package com.loopers.application.like;

import com.loopers.application.PageInfo;
import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.ProductInfoAssembler;
import com.loopers.domain.common.PageCondition;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductWithLikeCount;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class LikeFacade {
    private final LikeService likeService;
    private final ProductService productService;
    private final ProductInfoAssembler productInfoAssembler;
    private final UserService userService;

    public LikeInfo like(Long userId, Long productId) {
        userService.getUser(userId);
        productService.verifyActive(productId);
        likeService.like(userId, productId);
        return new LikeInfo(productId, true, likeService.countLikes(productId));
    }

    public LikeInfo unlike(Long userId, Long productId) {
        userService.getUser(userId);
        likeService.unlike(userId, productId);
        return new LikeInfo(productId, false, likeService.countLikes(productId));
    }

    public PageInfo<ProductInfo> getLikedProducts(Long requesterId, Long userId, int page, int size) {
        if (!requesterId.equals(userId)) {
            throw new DomainException(DomainErrorType.NOT_FOUND, "[userId = " + userId + "] 좋아요 목록을 찾을 수 없습니다.");
        }
        userService.getUser(userId);
        PageCondition pageCondition = new PageCondition(page, size);

        List<Product> products = productService.getLikedProducts(userId, pageCondition);
        Map<Long, Long> likeCounts = likeService.countLikes(products.stream().map(Product::getId).toList());
        List<ProductWithLikeCount> rows = products.stream()
            .map(product -> new ProductWithLikeCount(product, likeCounts.getOrDefault(product.getId(), 0L)))
            .toList();

        return PageInfo.of(productInfoAssembler.assemble(rows), page, size, productService.countLikedProducts(userId));
    }
}
