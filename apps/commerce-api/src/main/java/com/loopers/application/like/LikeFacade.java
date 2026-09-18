package com.loopers.application.like;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class LikeFacade {
    private final LikeService likeService;
    private final ProductService productService;
    private final ProductFacade productFacade;

    public void like(Long userId, Long productId) {
        productService.getProduct(productId);
        likeService.like(userId, productId);
    }

    public void unlike(Long userId, Long productId) {
        likeService.unlike(userId, productId);
    }

    public Page<ProductInfo> getMyLikedProducts(Long userId, Pageable pageable) {
        Page<LikeModel> likes = likeService.getLikesByUser(userId, pageable);
        List<Long> productIds = likes.getContent().stream().map(LikeModel::getProductId).toList();
        Map<Long, ProductInfo> infoByProductId = productFacade.getProductsByIds(productIds).stream()
            .collect(Collectors.toMap(ProductInfo::id, Function.identity()));
        List<ProductInfo> content = productIds.stream()
            .map(infoByProductId::get)
            .filter(Objects::nonNull)
            .toList();
        return new PageImpl<>(content, pageable, likes.getTotalElements());
    }
}
