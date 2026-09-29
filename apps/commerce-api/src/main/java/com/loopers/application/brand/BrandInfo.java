package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;

import java.time.ZonedDateTime;

public record BrandInfo(Long id, String name, String description, ZonedDateTime createdAt, ZonedDateTime updatedAt) {
    public static BrandInfo from(BrandModel brand) {
        return new BrandInfo(
            brand.getId(),
            brand.getName(),
            brand.getDescription(),
            brand.getCreatedAt(),
            brand.getUpdatedAt()
        );
    }
}
