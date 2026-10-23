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

    /**
     * 확보했던 수량을 현재 가용 수량에 더한다. 주문 이전 값으로 되돌리지 않으므로 그사이의 다른 변경을 덮지 않는다(설계 3.5).
     * 반환 수량은 고정 주문 품목에서 오므로 범위 초과는 입력 오류가 아닌 내부 정합성 오류다.
     */
    StockChange increase(long amount) {
        if (amount < 1L) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "반환 수량은 1 이상이어야 합니다.");
        }
        long before = this.quantity;
        long after;
        try {
            after = Math.addExact(before, amount);
        } catch (ArithmeticException e) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "재고 반환 결과가 표현 범위를 넘습니다.");
        }
        this.quantity = after;
        return new StockChange(before, after, amount);
    }

    private static void requireNotNegative(long quantity) {
        if (quantity < 0L) {
            throw new CoreException(ErrorType.INVALID_STOCK_QUANTITY);
        }
    }
}
