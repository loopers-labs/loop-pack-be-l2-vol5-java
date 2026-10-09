package com.loopers.application.brand.query;

/** 브랜드 조회 전용 DTO 묶음. 조회 하나당 중첩 record 하나. 엔티티를 담지 않는다 (DR-31). */
public final class BrandView {
    private BrandView() {
    }

    /** FR-BRAND-01 고객 상세. 설계 4-3-0 BrandSummary. */
    public record Summary(Long id, String name) {
    }

    /** FR-ADMIN-BRAND-01/03 관리자 목록·상세. 설계 4-3-0 BrandAdmin. 삭제된 브랜드도 포함 (ASM-15). */
    public record Admin(Long id, String name, boolean deleted) {
    }
}
