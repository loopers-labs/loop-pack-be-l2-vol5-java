package com.loopers.brand.domain;

/**
 * 브랜드가 수정·삭제됐다는 사실 (W4). 누가 듣는지는 모른다 — 지금은 상품 목록 캐시가 듣는다.
 */
public record BrandChangedEvent(Long brandId) {
}
