package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductAdminInfo;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;

public class ProductAdminV1Dto {

    public record CreateRequest(Long brandId, String name, Long price, Integer stock) {}

    /**
     * PRD-03: 수정은 이름·가격만 받는다. brandId를 보내도 반영하지 않는다.
     */
    public record UpdateRequest(String name, Long price) {}

    public record StockRequest(Integer stock) {}

    public record ProductResponse(
        Long id,
        Long brandId,
        String name,
        long price,
        int stock,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt
    ) {
        public static ProductResponse from(ProductAdminInfo info) {
            return new ProductResponse(
                info.id(),
                info.brandId(),
                info.name(),
                info.price(),
                info.stock(),
                info.createdAt(),
                info.updatedAt()
            );
        }
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "필수 필드 '" + field + "'이(가) 누락되었습니다.");
        }
        return value;
    }
}
