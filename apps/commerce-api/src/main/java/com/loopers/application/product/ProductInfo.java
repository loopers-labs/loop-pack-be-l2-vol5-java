package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;

import java.time.ZonedDateTime;

// 고객·관리자 조회가 공유하는 Info — 필드 노출 범위는 interfaces 계층의 응답 DTO가 결정한다
// (docs/week2/design.md 1번 섹션, 7번 섹션 참고).
public record ProductInfo(
    Long id,
    String name,
    Long price,
    Long brandId,
    int remainingStock,
    ZonedDateTime createdAt,
    ZonedDateTime updatedAt,
    ZonedDateTime deletedAt
) {
    public static ProductInfo from(ProductModel model) {
        return new ProductInfo(
            model.getId(),
            model.getName(),
            model.getPrice(),
            model.getBrandId(),
            model.getRemainingStock(),
            model.getCreatedAt(),
            model.getUpdatedAt(),
            model.getDeletedAt()
        );
    }
}
