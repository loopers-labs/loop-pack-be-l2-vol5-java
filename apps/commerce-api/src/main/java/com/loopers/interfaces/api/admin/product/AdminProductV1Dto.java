package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.AdminProductInfo;

import java.time.ZonedDateTime;

public class AdminProductV1Dto {
    /** 숫자 필드는 원시 타입이라 누락되면 역직렬화에서 400 으로 거절된다 (FAIL_ON_NULL_FOR_PRIMITIVES). */
    public record CreateRequest(long brandId, String name, long price) {}

    /** 브랜드는 바꿀 수 없어 brandId 를 두지 않는다. 보내도 무시된다 (PRD-03). */
    public record UpdateRequest(String name, long price) {}

    public record StockRequest(int stock) {}

    public record BrandSummary(Long id, String name) {}

    public record ProductResponse(
        Long id,
        String name,
        long price,
        int stock,
        BrandSummary brand,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt
    ) {
        public static ProductResponse from(AdminProductInfo info) {
            return new ProductResponse(
                info.id(),
                info.name(),
                info.price(),
                info.stock(),
                new BrandSummary(info.brandId(), info.brandName()),
                info.createdAt(),
                info.updatedAt()
            );
        }
    }
}
