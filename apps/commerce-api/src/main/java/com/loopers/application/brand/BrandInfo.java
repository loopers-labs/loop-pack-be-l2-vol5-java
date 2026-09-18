package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;

public record BrandInfo(long brandId, String name, boolean deleted) {
    public static BrandInfo from(Brand brand) { return new BrandInfo(brand.getId(), brand.getName(), brand.isDeleted()); }
}
