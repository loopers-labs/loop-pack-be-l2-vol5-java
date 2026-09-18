package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandAdminInfo;

import java.time.ZonedDateTime;

public class BrandAdminV1Dto {
    public record BrandRequest(String name) {}

    public record BrandResponse(Long id, String name, ZonedDateTime deletedAt) {
        public static BrandResponse from(BrandAdminInfo info) {
            return new BrandResponse(info.id(), info.name(), info.deletedAt());
        }
    }
}
