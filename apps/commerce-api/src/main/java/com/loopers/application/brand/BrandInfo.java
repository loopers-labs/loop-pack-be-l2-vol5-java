package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;

import java.time.ZonedDateTime;

// 고객·관리자 조회가 공유하는 Info — 필드 노출 범위는 interfaces 계층의 응답 DTO가 결정한다
// (docs/week2/design.md 1번 섹션, 7번 섹션 참고).
public record BrandInfo(
    Long id,
    String name,
    String description,
    String category,
    ZonedDateTime createdAt,
    ZonedDateTime updatedAt,
    ZonedDateTime deletedAt
) {
    public static BrandInfo from(BrandModel model) {
        return new BrandInfo(
            model.getId(),
            model.getName(),
            model.getDescription(),
            model.getCategory(),
            model.getCreatedAt(),
            model.getUpdatedAt(),
            model.getDeletedAt()
        );
    }
}
