package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;

// 브랜드 키(brand:{id})에 저장하는 값 — 상품 키와 분리해 브랜드 수정 시 무효화가 키 하나로 끝나게 한다 (docs/week4/design.md 6번 섹션).
public record CachedBrand(Long id, String name) {

    public static CachedBrand from(BrandModel brand) {
        return new CachedBrand(brand.getId(), brand.getName());
    }
}
