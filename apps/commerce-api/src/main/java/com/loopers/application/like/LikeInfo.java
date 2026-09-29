package com.loopers.application.like;

import com.loopers.domain.product.Product;

public record LikeInfo(Long productId, String productName, long price, long likeCount) {
    public static LikeInfo of(Product product, long likeCount) {
        return new LikeInfo(
            product.getId(),
            product.getName(),
            product.getPrice().getAmount(),
            likeCount
        );
    }
}
