package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;

import java.time.ZonedDateTime;

/**
 * 관리자 상품 결과: 운영에 필요한 재고·브랜드 ID·시각을 포함한다.
 */
public record ProductAdminInfo(
    Long id,
    Long brandId,
    String name,
    long price,
    int stock,
    ZonedDateTime createdAt,
    ZonedDateTime updatedAt
) {
    public static ProductAdminInfo from(ProductModel product) {
        return new ProductAdminInfo(
            product.getId(),
            product.getBrandId(),
            product.getName(),
            product.getPrice().amount(),
            product.getStock(),
            product.getCreatedAt(),
            product.getUpdatedAt()
        );
    }
}
