package com.loopers.domains.brand.app;

import com.loopers.domains.brand.domain.Brand;

public record BrandResult(long id, String name) {
    static BrandResult from(Brand brand) {
        return new BrandResult(brand.getId().value(), brand.getName());
    }
}
