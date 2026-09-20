package com.loopers.application.catalog;

import com.loopers.domain.catalog.BrandModel;
import com.loopers.domain.catalog.ProductModel;

/** 설계 4-3-0 ProductSummary(고객) / ProductAdmin(관리자) 의 원천. 좋아요 수는 INV-05 계산값. */
public record ProductInfo(
    Long id,
    String name,
    Long price,
    Integer stock,
    BrandInfo brand,
    long likeCount,
    boolean deleted
) {
    public static ProductInfo of(ProductModel product, BrandModel brand, long likeCount) {
        return new ProductInfo(
            product.getId(),
            product.getName(),
            product.getPrice(),
            product.getStock(),
            BrandInfo.from(brand),
            likeCount,
            product.isDeleted()
        );
    }
}
