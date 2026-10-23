package com.loopers.application.product.dto;

import com.loopers.domain.product.Product;

import java.time.ZonedDateTime;

/**
 * 상품 조회 결과. 어떤 필드를 응답에 담을지는 각 Dto 가 고른다.
 */
public record ProductInfo(
    Long id,
    Long brandId,
    String name,
    long price,
    int stock,
    ZonedDateTime createdAt,
    ZonedDateTime updatedAt
) {
    public static ProductInfo from(Product product) {
        return new ProductInfo(
            product.getId(),
            product.getBrandId(),
            product.getName(),
            product.getPrice().amount(),
            product.getStock().remaining(),
            product.getCreatedAt(),
            product.getUpdatedAt()
        );
    }
}
