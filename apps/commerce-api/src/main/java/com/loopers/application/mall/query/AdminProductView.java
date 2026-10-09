package com.loopers.application.mall.query;

import java.time.Instant;

// 관리자용 상품 조회 결과
public record AdminProductView(long productId, String name, long price, BrandSummaryView brand, long likeCount,
                           String description, int stock, Instant createdAt) {}
