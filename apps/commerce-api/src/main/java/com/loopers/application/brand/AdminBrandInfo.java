package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;

import java.time.ZonedDateTime;

public record AdminBrandInfo(Long id, String name, ZonedDateTime createdAt, ZonedDateTime updatedAt) {
    public static AdminBrandInfo from(Brand brand) {
        return new AdminBrandInfo(brand.getId(), brand.getName(), brand.getCreatedAt(), brand.getUpdatedAt());
    }
}
