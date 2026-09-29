package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikedProductInfo;
import com.loopers.interfaces.api.product.ProductV1Dto;

import java.time.ZonedDateTime;

public class LikeV1Dto {

    public record LikeResponse(Long productId, boolean liked) {}

    public record LikedProductResponse(
        Long productId,
        String name,
        long price,
        ProductV1Dto.BrandSummary brand,
        ZonedDateTime likedAt
    ) {
        public static LikedProductResponse from(LikedProductInfo info) {
            return new LikedProductResponse(
                info.productId(),
                info.name(),
                info.price(),
                new ProductV1Dto.BrandSummary(info.brandId(), info.brandName()),
                info.likedAt()
            );
        }
    }
}
