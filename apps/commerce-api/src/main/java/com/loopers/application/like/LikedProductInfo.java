package com.loopers.application.like;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.product.ProductModel;

import java.time.ZonedDateTime;

public record LikedProductInfo(
    Long productId,
    String name,
    long price,
    Long brandId,
    String brandName,
    ZonedDateTime likedAt
) {
    public static LikedProductInfo of(LikeModel like, ProductModel product, BrandModel brand) {
        return new LikedProductInfo(
            product.getId(),
            product.getName(),
            product.getPrice().amount(),
            product.getBrandId(),
            brand == null ? null : brand.getName(),
            like.getCreatedAt()
        );
    }
}
