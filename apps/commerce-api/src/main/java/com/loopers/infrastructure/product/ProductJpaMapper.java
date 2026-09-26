package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaEntity;

final class ProductJpaMapper {

    private ProductJpaMapper() {}

    static Product toDomain(ProductJpaEntity entity) {
        return Product.reconstitute(
            entity.getId(),
            entity.getBrand().getId(),
            entity.getName(),
            entity.getPrice(),
            entity.getStock(),
            entity.getCreatedAt(),
            entity.getDeletedAt()
        );
    }

    static ProductJpaEntity toNewEntity(Product product, BrandJpaEntity brand) {
        ProductJpaEntity entity = ProductJpaEntity.create(
            brand,
            product.getName(),
            product.getPrice(),
            product.getStock().amount()
        );
        if (product.getDeletedAt() != null) {
            entity.delete(product.getDeletedAt());
        }
        return entity;
    }

    static void update(Product product, ProductJpaEntity entity) {
        entity.updateDetails(product.getName(), product.getPrice());
        entity.changeStockTo(product.getStock().amount());
        if (product.getDeletedAt() != null && entity.getDeletedAt() == null) {
            entity.delete(product.getDeletedAt());
        }
    }
}
