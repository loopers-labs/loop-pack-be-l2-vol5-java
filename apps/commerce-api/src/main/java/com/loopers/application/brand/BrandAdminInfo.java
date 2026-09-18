package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;

import java.time.ZonedDateTime;

public record BrandAdminInfo(Long id, String name, ZonedDateTime deletedAt) {
    public static BrandAdminInfo from(BrandModel model) {
        return new BrandAdminInfo(
            model.getId(),
            model.getName(),
            model.getDeletedAt()
        );
    }
}
