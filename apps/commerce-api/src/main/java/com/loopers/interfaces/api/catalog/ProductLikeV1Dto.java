package com.loopers.interfaces.api.catalog;

import com.loopers.application.catalog.LikeInfo;

import java.time.ZonedDateTime;

public class ProductLikeV1Dto {
    /** 설계 4-3-0 LikeItem. product 는 좋아요 수 없이 상품 정보 + 브랜드. */
    public record LikeResponse(ZonedDateTime likedAt, LikedProduct product) {
        public static LikeResponse from(LikeInfo info) {
            return new LikeResponse(info.likedAt(), LikedProduct.from(info));
        }
    }

    public record LikedProduct(Long id, String name, Long price, BrandV1Dto.BrandResponse brand) {
        static LikedProduct from(LikeInfo info) {
            return new LikedProduct(
                info.product().id(), info.product().name(), info.product().price(),
                BrandV1Dto.BrandResponse.from(info.product().brand()));
        }
    }
}
