package com.loopers.product.adapter.in.web.dto;

import com.loopers.product.application.port.in.ProductInfo;
import com.loopers.product.domain.ProductSort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class ProductDto {

    /**
     * 고객 상품 응답. 재고 수량·시각은 넣지 않는다 (P-11, 7-2).
     */
    public record ProductResponse(Long id, String name, long price, BrandSummary brand, long likeCount) {
        public static ProductResponse from(ProductInfo info) {
            return new ProductResponse(
                info.id(),
                info.name(),
                info.price(),
                new BrandSummary(info.brandId(), info.brandName()),
                info.likeCount()
            );
        }
    }

    public record BrandSummary(Long id, String name) {}

    /**
     * 정렬 입력: latest(기본)·price_asc·likes_desc. 그 밖의 값은 400.
     */
    public static ProductSort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return ProductSort.LATEST;
        }
        return switch (sort) {
            case "latest" -> ProductSort.LATEST;
            case "price_asc" -> ProductSort.PRICE_ASC;
            case "likes_desc" -> ProductSort.LIKES_DESC;
            default -> throw new CoreException(ErrorType.BAD_REQUEST, "sort는 latest, price_asc, likes_desc 중 하나여야 합니다.");
        };
    }
}
