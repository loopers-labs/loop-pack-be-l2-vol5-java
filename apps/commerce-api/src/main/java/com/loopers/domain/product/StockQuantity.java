package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public record StockQuantity(long amount) {

    public StockQuantity {
        if (amount < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고 수량은 0 이상이어야 합니다.");
        }
    }

    public StockQuantity decrease(long quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감 수량은 양수여야 합니다.");
        }
        if (amount < quantity) {
            throw new CoreException(ErrorType.CONFLICT, "재고가 부족합니다.");
        }
        return new StockQuantity(amount - quantity);
    }
}
