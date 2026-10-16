package com.loopers.application.mall.event;

// 커밋 후 브랜드 정보가 포함된 소속 상품 상세 조회 복사본을 무효화한다.
public record BrandProductsChangedEvent(long brandId) {}
