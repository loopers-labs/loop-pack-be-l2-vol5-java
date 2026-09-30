package com.loopers.application.mall.result;

import com.loopers.domain.mall.model.Brand;

// 브랜드 생성·수정 결과
public record BrandResult(long brandId, String name, String description) {
    public static BrandResult from(Brand brand) {
        return new BrandResult(brand.getId(), brand.getName(), brand.getDescription());
    }
}
