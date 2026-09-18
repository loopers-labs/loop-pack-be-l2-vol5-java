package com.loopers.application.product;

import com.loopers.application.brand.BrandInfo;
import com.loopers.domain.product.Product;

import java.time.ZonedDateTime;

public record AdminProductInfo(Long productId, String name, long price, int stockQuantity,
                               BrandInfo brand, long likeCount, ZonedDateTime createdAt, ZonedDateTime updatedAt,
                               ZonedDateTime deletedAt) {

    public static AdminProductInfo from(Product product, long likeCount) {
        return new AdminProductInfo(product.getId(), product.getName(), product.getPrice(),
            product.getStockQuantity(), new BrandInfo(product.getBrand().getId(), product.getBrand().getName()),
            likeCount, product.getCreatedAt(), product.getUpdatedAt(), product.getDeletedAt());
    }
}
