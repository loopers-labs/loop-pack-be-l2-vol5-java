package com.loopers.domains.product.app.port;

// 고객 상품 목록 조회 조건. brandId가 null이면 전체 브랜드다.
public record ProductListQuery(Long brandId, String sort, int page, int size) { }
