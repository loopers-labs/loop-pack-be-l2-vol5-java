package com.loopers.domain.like;

import java.time.ZonedDateTime;

/** 내 좋아요 목록의 조회 결과 타입. 상품 요약과 좋아요 시각을 담는다 (설계 6.3). */
public record LikedProduct(
    Long productId,
    String name,
    long price,
    int stock,
    long likeCount,
    Long brandId,
    String brandName,
    ZonedDateTime likedAt
) {}
