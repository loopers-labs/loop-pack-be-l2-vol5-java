package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandInfo;

import java.time.ZonedDateTime;

public class AdminBrandV1Dto {
    public record BrandRequest(String name, String description) {}

    public record BrandResponse(Long id, String name, String description, ZonedDateTime createdAt, ZonedDateTime updatedAt) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(
                info.id(),
                info.name(),
                info.description(),
                info.createdAt(),
                info.updatedAt()
            );
        }
    }
}
