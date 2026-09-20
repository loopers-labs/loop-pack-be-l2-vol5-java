package com.loopers.interfaces.api.catalog;

import com.loopers.application.catalog.ProductInfo;

public class ProductV1Dto {
    /** 설계 4-3-0 ProductSummary (고객). 재고는 넣지 않는다 (DR-22). */
    public record ProductResponse(Long id, String name, Long price, BrandV1Dto.BrandResponse brand, long likeCount) {
        public static ProductResponse from(ProductInfo info) {
            return new ProductResponse(
                info.id(), info.name(), info.price(), BrandV1Dto.BrandResponse.from(info.brand()), info.likeCount());
        }
    }
}
