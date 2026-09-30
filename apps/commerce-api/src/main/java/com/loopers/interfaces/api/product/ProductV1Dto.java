package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductDetailInfo;
import org.springframework.data.domain.Page;

import java.util.List;

public class ProductV1Dto {
    public record BrandSummaryResponse(Long id, String name) {
        public static BrandSummaryResponse from(ProductDetailInfo.BrandSummary brand) {
            return new BrandSummaryResponse(brand.id(), brand.name());
        }
    }

    public record ProductResponse(
        Long id,
        String name,
        Long price,
        int remainingStock,
        BrandSummaryResponse brand,
        long likeCount
    ) {
        public static ProductResponse from(ProductDetailInfo info) {
            return new ProductResponse(
                info.id(),
                info.name(),
                info.price(),
                info.remainingStock(),
                BrandSummaryResponse.from(info.brand()),
                info.likeCount()
            );
        }
    }

    public record ProductListResponse(
        List<ProductResponse> products,
        int page,
        int size,
        long totalElements,
        int totalPages
    ) {
        public static ProductListResponse from(Page<ProductDetailInfo> page) {
            return new ProductListResponse(
                page.getContent().stream().map(ProductResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
            );
        }
    }
}
