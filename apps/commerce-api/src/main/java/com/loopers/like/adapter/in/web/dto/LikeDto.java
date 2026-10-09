package com.loopers.like.adapter.in.web.dto;

import com.loopers.like.application.port.in.LikedProductInfo;
import com.loopers.product.adapter.in.web.dto.ProductDto;

import java.time.ZonedDateTime;

public class LikeDto {

    public record LikeResponse(Long productId, boolean liked) {}

    public record LikedProductResponse(
        Long productId,
        String name,
        long price,
        ProductDto.BrandSummary brand,
        ZonedDateTime likedAt
    ) {
        public static LikedProductResponse from(LikedProductInfo info) {
            return new LikedProductResponse(
                info.productId(),
                info.name(),
                info.price(),
                new ProductDto.BrandSummary(info.brandId(), info.brandName()),
                info.likedAt()
            );
        }
    }
}
