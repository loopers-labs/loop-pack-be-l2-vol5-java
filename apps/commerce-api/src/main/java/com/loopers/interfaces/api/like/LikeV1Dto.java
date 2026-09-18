package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeInfo;
import com.loopers.application.like.LikedProductInfo;

import java.time.ZonedDateTime;

public class LikeV1Dto {
    public record LikeResponse(Long productId, long likeCount) {
        public static LikeResponse from(LikeInfo info) {
            return new LikeResponse(info.productId(), info.likeCount());
        }
    }

    public record BrandSummary(Long id, String name) {}

    /** 상품 요약과 좋아요 시각. 재고 수량 대신 품절 여부만 내보낸다. */
    public record LikedProductResponse(
        Long productId,
        String name,
        long price,
        long likeCount,
        boolean soldOut,
        BrandSummary brand,
        ZonedDateTime likedAt
    ) {
        public static LikedProductResponse from(LikedProductInfo info) {
            return new LikedProductResponse(
                info.productId(),
                info.name(),
                info.price(),
                info.likeCount(),
                info.stock() == 0,
                new BrandSummary(info.brandId(), info.brandName()),
                info.likedAt()
            );
        }
    }
}
