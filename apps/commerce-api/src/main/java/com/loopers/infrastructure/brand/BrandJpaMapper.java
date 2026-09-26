package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;

final class BrandJpaMapper {

    private BrandJpaMapper() {}

    static Brand toDomain(BrandJpaEntity entity) {
        return Brand.reconstitute(entity.getId(), entity.getName(), entity.getCreatedAt(), entity.getDeletedAt());
    }

    static BrandJpaEntity toNewEntity(Brand brand) {
        BrandJpaEntity entity = BrandJpaEntity.create(brand.getName());
        if (brand.getDeletedAt() != null) {
            entity.delete(brand.getDeletedAt());
        }
        return entity;
    }

    static void update(Brand brand, BrandJpaEntity entity) {
        entity.rename(brand.getName());
        if (brand.getDeletedAt() != null && entity.getDeletedAt() == null) {
            entity.delete(brand.getDeletedAt());
        }
    }
}
