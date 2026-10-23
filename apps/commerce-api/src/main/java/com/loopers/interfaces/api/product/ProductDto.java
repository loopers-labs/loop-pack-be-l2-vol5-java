package com.loopers.interfaces.api.product;

import com.loopers.application.product.dto.ProductDetailInfo;
import com.loopers.application.product.dto.ProductListItemInfo;

public class ProductDto {
    // 고객에게는 구매 판단에 필요한 값만 노출한다. 재고 수량·품절 여부와 생성·수정 시각은 담지 않는다(재고는 관리자 응답에만).
    public record BrandSummary(Long id, String name) {
    }

    public record ProductResponse(Long id, String name, long price, BrandSummary brand, long likeCount) {
        public static ProductResponse from(ProductDetailInfo info) {
            return new ProductResponse(
                info.product().id(),
                info.product().name(),
                info.product().price(),
                new BrandSummary(info.brand().id(), info.brand().name()),
                info.likeCount()
            );
        }

        public static ProductResponse from(ProductListItemInfo info) {
            return new ProductResponse(
                info.id(),
                info.name(),
                info.price(),
                new BrandSummary(info.brand().id(), info.brand().name()),
                info.likeCount()
            );
        }
    }
}
