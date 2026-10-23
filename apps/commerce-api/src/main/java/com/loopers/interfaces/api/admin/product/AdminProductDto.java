package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.dto.ProductInfo;
import jakarta.validation.constraints.NotNull;

import java.time.ZonedDateTime;

public class AdminProductDto {

    // 필수 누락(@NotNull)은 요청 형식 오류라 여기서 거절한다. 공백·길이·범위 같은 값 규칙은 Product 가 판단한다.
    public record CreateRequest(
        @NotNull Long brandId,
        @NotNull String name,
        @NotNull Long price,
        @NotNull Integer stock
    ) {}

    public record UpdateRequest(@NotNull String name, @NotNull Long price) {}

    public record StockRequest(@NotNull Integer stock) {}

    public record ProductResponse(
        Long id,
        Long brandId,
        String name,
        long price,
        int stock,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt
    ) {
        public static ProductResponse from(ProductInfo info) {
            return new ProductResponse(
                info.id(), info.brandId(), info.name(), info.price(), info.stock(), info.createdAt(), info.updatedAt()
            );
        }
    }
}
