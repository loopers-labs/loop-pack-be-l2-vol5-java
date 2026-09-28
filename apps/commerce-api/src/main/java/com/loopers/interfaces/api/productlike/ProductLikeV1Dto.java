package com.loopers.interfaces.api.productlike;

import com.loopers.interfaces.api.brand.BrandV1Dto;

import com.loopers.application.productlike.query.ProductLikeView;

import java.time.ZonedDateTime;

public class ProductLikeV1Dto {
    /** 설계 4-3-0 LikeItem. product 는 좋아요 수 없이 상품 정보 + 브랜드. */
    public record LikeResponse(ZonedDateTime likedAt, LikedProduct product) {
        public static LikeResponse from(ProductLikeView.Item view) {
            return new LikeResponse(view.likedAt(), LikedProduct.from(view));
        }
    }

    public record LikedProduct(Long id, String name, Long price, BrandV1Dto.BrandResponse brand) {
        static LikedProduct from(ProductLikeView.Item view) {
            return new LikedProduct(
                view.productId(), view.productName(), view.productPrice(),
                new BrandV1Dto.BrandResponse(view.brandId(), view.brandName()));
        }
    }
}
