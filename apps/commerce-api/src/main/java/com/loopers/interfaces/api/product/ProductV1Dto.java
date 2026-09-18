package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductInfo;

public class ProductV1Dto {

    public record BrandSummary(Long id, String name) {}

    public record BrandDetail(Long id, String name, String description) {}

    /** 고객에게는 재고 수량 대신 품절 여부만 내보낸다 (설계 2.4). */
    public record ProductSummaryResponse(Long id, String name, long price, long likeCount, boolean soldOut, BrandSummary brand) {
        public static ProductSummaryResponse from(ProductInfo info) {
            return new ProductSummaryResponse(
                info.id(),
                info.name(),
                info.price(),
                info.likeCount(),
                info.stock() == 0,
                new BrandSummary(info.brandId(), info.brandName())
            );
        }
    }

    public record ProductDetailResponse(Long id, String name, long price, long likeCount, boolean soldOut, BrandDetail brand) {
        public static ProductDetailResponse from(ProductInfo info) {
            return new ProductDetailResponse(
                info.id(),
                info.name(),
                info.price(),
                info.likeCount(),
                info.stock() == 0,
                new BrandDetail(info.brandId(), info.brandName(), info.brandDescription())
            );
        }
    }
}
