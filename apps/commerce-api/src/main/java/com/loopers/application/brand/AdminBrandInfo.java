package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;

import java.time.ZonedDateTime;

public record AdminBrandInfo(Long brandId, String name, ZonedDateTime createdAt,
                             ZonedDateTime updatedAt, ZonedDateTime deletedAt) {

    public static AdminBrandInfo from(Brand brand) {
        return new AdminBrandInfo(brand.getId(), brand.getName(), brand.getCreatedAt(),
            brand.getUpdatedAt(), brand.getDeletedAt());
    }
}
