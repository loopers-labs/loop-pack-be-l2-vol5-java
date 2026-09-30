package com.loopers.application.shopping.query;

import com.loopers.application.mall.query.BrandSummaryView;
import java.time.Instant;

// 좋아요 목록 조회 결과 항목
public record LikedProductView(long productId, String name, long price, BrandSummaryView brand, long likeCount, Instant likedAt) {}
