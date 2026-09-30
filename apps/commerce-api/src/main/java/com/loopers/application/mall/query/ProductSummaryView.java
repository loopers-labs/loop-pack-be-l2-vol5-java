package com.loopers.application.mall.query;

// 상품 목록용 요약 정보
public record ProductSummaryView(long productId, String name, long price, BrandSummaryView brand, long likeCount) {}
