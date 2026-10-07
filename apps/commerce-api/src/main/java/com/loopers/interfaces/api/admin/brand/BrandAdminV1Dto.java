package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandAdminInfo;

public class BrandAdminV1Dto {
    public record CreateRequest(String name) {
    }

    public record UpdateRequest(String name) {
    }

    public record BrandResponse(Long id, String name, boolean deleted) {
        public static BrandResponse from(BrandAdminInfo info) {
            return new BrandResponse(info.id(), info.name(), info.deleted());
        }
    }
}
