package com.loopers.application.mall.query;

// 상품 상세 조회 결과
public record ProductDetailView(long productId, String name, long price, BrandSummaryView brand, long likeCount,
                            String description, int stock) {}
