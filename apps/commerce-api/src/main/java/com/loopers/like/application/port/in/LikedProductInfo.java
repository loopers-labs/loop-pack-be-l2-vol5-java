package com.loopers.like.application.port.in;

import com.loopers.brand.domain.BrandModel;
import com.loopers.like.domain.LikeModel;
import com.loopers.product.domain.ProductModel;

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
