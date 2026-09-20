package com.loopers.application.catalog;

import com.loopers.domain.catalog.BrandModel;

/** 설계 4-3-0 BrandSummary(고객: id, name) / BrandAdmin(+ deleted) 의 원천. */
public record BrandInfo(Long id, String name, boolean deleted) {
    public static BrandInfo from(BrandModel brand) {
        return new BrandInfo(brand.getId(), brand.getName(), brand.isDeleted());
    }
}
