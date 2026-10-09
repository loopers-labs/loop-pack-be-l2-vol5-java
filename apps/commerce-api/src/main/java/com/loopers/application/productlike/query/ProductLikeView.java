package com.loopers.application.productlike.query;

import java.time.ZonedDateTime;

/** 좋아요 조회 전용 DTO 묶음. 조회 하나당 중첩 record 하나. 엔티티를 담지 않는다 (DR-31). */
public final class ProductLikeView {
    private ProductLikeView() {
    }

    /** FR-LIKE-03 내 좋아요 목록 한 행. 설계 4-3-0 LikeItem. 상품 정보 + 브랜드, 좋아요 수는 없다. */
    public record Item(
        ZonedDateTime likedAt, Long productId, String productName, Long productPrice, Long brandId, String brandName
    ) {
    }
}
