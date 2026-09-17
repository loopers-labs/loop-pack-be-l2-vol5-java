package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

/**
 * 상품의 현재 재고 수량. 음수 재고를 허용하지 않으며
 * 소유자인 Product 의 행동을 통해서만 변경한다.
 */
@Embeddable
@Getter
public class Stock {

    @Column(name = "stock_quantity", nullable = false)
    private long quantity;

    protected Stock() {}

    private Stock(long quantity) {
        this.quantity = quantity;
    }

    public static Stock of(long quantity) {
        requireNotNegative(quantity);
        return new Stock(quantity);
    }

    /** 최종 수량으로 변경한다. quantity 는 증감량이 아니라 변경 후의 수량이다. */
    StockChange change(long finalQuantity) {
        requireNotNegative(finalQuantity);

        long before = this.quantity;
        this.quantity = finalQuantity;
        return new StockChange(before, finalQuantity, Math.abs(finalQuantity - before));
    }

    StockChange decrease(long amount) {
        if (amount < 1L) {
            throw new CoreException(ErrorType.INVALID_STOCK_QUANTITY, "차감 수량은 1 이상이어야 합니다.");
        }

        long before = this.quantity;
        if (before < amount) {
            throw new CoreException(ErrorType.INSUFFICIENT_STOCK);
        }

        long after = before - amount;
        this.quantity = after;
        return new StockChange(before, after, amount);
    }

    private static void requireNotNegative(long quantity) {
        if (quantity < 0L) {
            throw new CoreException(ErrorType.INVALID_STOCK_QUANTITY);
        }
    }
}
