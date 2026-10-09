package com.loopers.application.product.query;

/** 상품 조회 전용 DTO 묶음. 조회 하나당 중첩 record 하나. 엔티티를 담지 않는다 (DR-31). */
public final class ProductView {
    private ProductView() {
    }

    /** FR-PRODUCT-01/02 고객 목록·상세. 설계 4-3-0 ProductSummary. 재고는 넣지 않는다 (DR-22). 좋아요 수는 INV-05 계산값. */
    public record Summary(Long id, String name, Long price, Long brandId, String brandName, long likeCount) {
    }

    /** FR-ADMIN-PRODUCT-01/03 관리자 목록·상세. 설계 4-3-0 ProductAdmin. 삭제된 상품·브랜드도 포함 (ASM-15). */
    public record Admin(
        Long id, String name, Long price, Integer stock, Long brandId, String brandName, long likeCount, boolean deleted
    ) {
    }
}
