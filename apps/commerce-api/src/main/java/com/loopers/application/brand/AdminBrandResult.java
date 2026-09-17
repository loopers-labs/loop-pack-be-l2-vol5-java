package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;

public record AdminBrandResult(long id, String name, boolean deleted) {
    static AdminBrandResult from(Brand brand) {
        return new AdminBrandResult(brand.getId().value(), brand.getName(), brand.isDeleted());
    }
}
