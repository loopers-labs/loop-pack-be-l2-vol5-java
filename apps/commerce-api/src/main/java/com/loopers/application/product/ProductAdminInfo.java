package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;

public record ProductAdminInfo(Long id, Long brandId, String name, Long price, int stock, boolean deleted) {
    public static ProductAdminInfo from(ProductModel model) {
        return new ProductAdminInfo(
            model.getId(),
            model.getBrandId(),
            model.getName(),
            model.getPrice(),
            model.getStock(),
            model.getDeletedAt() != null
        );
    }
}
