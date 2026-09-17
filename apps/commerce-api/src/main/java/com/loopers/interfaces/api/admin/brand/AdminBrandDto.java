package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.AdminBrandInfo;

import java.time.ZonedDateTime;

public class AdminBrandDto {
    public record BrandRequest(String name) {}

    public record BrandResponse(Long brandId, String name, ZonedDateTime createdAt, ZonedDateTime updatedAt) {
        public static BrandResponse from(AdminBrandInfo info) {
            return new BrandResponse(info.id(), info.name(), info.createdAt(), info.updatedAt());
        }
    }
}
