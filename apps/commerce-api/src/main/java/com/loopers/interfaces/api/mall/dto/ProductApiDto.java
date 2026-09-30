package com.loopers.interfaces.api.mall.dto;

import com.loopers.application.mall.command.ProductCommand;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;

// 상품 API 요청·응답 DTO 모음
public final class ProductApiDto {
    private ProductApiDto() {}

    public record CreateRequest(Long brandId, String name, String description, Long price, Integer stock) {
        public ProductCommand.Create toCommand() {
            if (brandId == null || price == null || stock == null) {
                throw new DomainException(DomainErrorCode.INVALID_QUANTITY, "상품 필수 입력이 누락되었습니다.");
            }
            if (brandId <= 0) {
                throw new DomainException(DomainErrorCode.INVALID_QUANTITY, "브랜드 ID는 양의 정수여야 합니다.");
            }
            return new ProductCommand.Create(brandId, name, description, price, stock);
        }
    }

    public record UpdateRequest(String name, String description, Long price) {
        public ProductCommand.Update toCommand(long productId) {
            if (price == null) {
                throw new DomainException(DomainErrorCode.NON_POSITIVE_MONEY);
            }
            return new ProductCommand.Update(productId, name, description, price);
        }
    }

    public record StockRequest(Integer stock) {
        public ProductCommand.SetStock toCommand(long productId) {
            if (stock == null) {
                throw new DomainException(DomainErrorCode.INVALID_STOCK);
            }
            return new ProductCommand.SetStock(productId, stock);
        }
    }
}
