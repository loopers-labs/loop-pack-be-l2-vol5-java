package com.loopers.brand.app;

import com.loopers.brand.domain.Brand;

public record BrandResult(long id, String name) {
    static BrandResult from(Brand brand) {
        return new BrandResult(brand.getId().value(), brand.getName());
    }
}
