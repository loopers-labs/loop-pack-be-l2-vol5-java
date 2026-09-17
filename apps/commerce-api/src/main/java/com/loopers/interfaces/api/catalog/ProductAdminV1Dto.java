package com.loopers.interfaces.api.catalog;

import com.loopers.application.catalog.ProductInfo;
import com.loopers.application.catalog.StockInfo;

public class ProductAdminV1Dto {
    /** EP-20 요청. 검증은 Model (ER-19/20/21), 브랜드는 Facade (ER-03). */
    public record CreateRequest(Long brandId, String name, Long price, Integer stock) {
    }

    /** EP-22 요청. 브랜드·재고는 받지 않는다 (ASM-16). */
    public record UpdateRequest(String name, Long price) {
    }

    /** EP-24 요청. 최종 수량. */
    public record StockRequest(Integer stock) {
    }

    /** 설계 4-3-0 ProductAdmin. */
    public record ProductAdminResponse(
        Long id, String name, Long price, Integer stock, BrandV1Dto.BrandResponse brand, long likeCount, boolean deleted
    ) {
        public static ProductAdminResponse from(ProductInfo info) {
            return new ProductAdminResponse(
                info.id(), info.name(), info.price(), info.stock(),
                BrandV1Dto.BrandResponse.from(info.brand()), info.likeCount(), info.deleted());
        }
    }

    /** 설계 4-3-0 StockResponse. */
    public record StockResponse(Long productId, Integer stock) {
        public static StockResponse from(StockInfo info) {
            return new StockResponse(info.productId(), info.stock());
        }
    }
}
