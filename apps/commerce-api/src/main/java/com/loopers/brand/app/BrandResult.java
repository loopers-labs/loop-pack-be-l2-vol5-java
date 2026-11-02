package com.loopers.brand.app;

import com.loopers.brand.domain.Brand;
import com.loopers.brand.domain.BrandId;

public record BrandResult(BrandId id, String name) {
    static BrandResult from(Brand brand) {
        return new BrandResult(brand.getId(), brand.getName());
    }
}
