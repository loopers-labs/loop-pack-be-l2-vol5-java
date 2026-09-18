package com.loopers.brand.interfaces;

import com.loopers.brand.domain.Brand;

public class BrandV1Dto {

    public record BrandRequest(String name) {
    }

    public record CustomerBrandResponse(Long id, String name) {
        public static CustomerBrandResponse from(Brand brand) {
            return new CustomerBrandResponse(brand.getId(), brand.getName());
        }
    }

    public record AdminBrandResponse(Long id, String name, boolean deleted) {
        public static AdminBrandResponse from(Brand brand) {
            return new AdminBrandResponse(brand.getId(), brand.getName(), brand.isDeleted());
        }
    }
}
