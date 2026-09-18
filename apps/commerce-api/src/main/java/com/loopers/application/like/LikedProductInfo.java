package com.loopers.application.like;

import com.loopers.domain.like.LikedProduct;

import java.time.ZonedDateTime;

public record LikedProductInfo(
    Long productId,
    String name,
    long price,
    int stock,
    long likeCount,
    Long brandId,
    String brandName,
    ZonedDateTime likedAt
) {
    public static LikedProductInfo from(LikedProduct likedProduct) {
        return new LikedProductInfo(
            likedProduct.productId(),
            likedProduct.name(),
            likedProduct.price(),
            likedProduct.stock(),
            likedProduct.likeCount(),
            likedProduct.brandId(),
            likedProduct.brandName(),
            likedProduct.likedAt()
        );
    }
}
