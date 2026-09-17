package com.loopers.interfaces.api.brand;

import com.loopers.domain.brand.BrandModel;

public class BrandAdminV1Dto {

    public record BrandSaveRequest(String name) {
    }

    public record AdminBrandResponse(Long id, String name) {
        public static AdminBrandResponse from(BrandModel brand) {
            return new AdminBrandResponse(brand.getId(), brand.getName());
        }
    }
}
