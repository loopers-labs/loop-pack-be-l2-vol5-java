package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandId;

public record BrandResult(BrandId id, String name) {
    static BrandResult from(Brand brand) {
        return new BrandResult(brand.getId(), brand.getName());
    }
}
