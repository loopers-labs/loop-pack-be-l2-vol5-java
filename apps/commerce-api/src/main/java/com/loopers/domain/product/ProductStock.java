package com.loopers.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 상품이 소유하는 가용 재고 수량과 최종 수량 설정·차감·예약 반환 규칙을 관리한다.
 */
@Embeddable
public class ProductStock {

    @Column(name = "stock_quantity", nullable = false)
    private int quantity;

    protected ProductStock() {
    }

    public ProductStock(int quantity) {
        validateNonNegativeQuantity(quantity);
        this.quantity = quantity;
    }

    public int getQuantity() {
        return quantity;
    }

    public static void validateCapacity(int available, long heldQuantity) {
        validateNonNegativeQuantity(available);
        if (heldQuantity < 0) {
            throw new IllegalStateException("Held reservation quantity must not be negative");
        }
        if (heldQuantity > (long) Integer.MAX_VALUE - available) {
            throw new ProductStockException(ProductStockException.Reason.STOCK_CAPACITY_EXCEEDED);
        }
    }

    public void changeQuantityTo(int quantity) {
        validateNonNegativeQuantity(quantity);
        this.quantity = quantity;
    }

    public void deduct(int deductionQuantity) {
        validateDeduction(deductionQuantity);
        quantity -= deductionQuantity;
    }

    public void validateDeduction(int deductionQuantity) {
        if (deductionQuantity <= 0) {
            throw new ProductStockException(ProductStockException.Reason.INVALID_DEDUCTION_QUANTITY);
        }
        if (deductionQuantity > quantity) {
            throw new ProductStockException(ProductStockException.Reason.INSUFFICIENT_STOCK);
        }
    }

    public void validateReturn(int returnQuantity) {
        if (returnQuantity <= 0) {
            throw new IllegalArgumentException("Reserved stock return quantity must be positive");
        }
        if ((long) quantity + returnQuantity > Integer.MAX_VALUE) {
            throw new IllegalStateException("Reserved stock return exceeds the storage limit");
        }
    }

    public void returnReserved(int returnQuantity) {
        validateReturn(returnQuantity);
        quantity += returnQuantity;
    }

    private static void validateNonNegativeQuantity(int quantity) {
        if (quantity < 0) {
            throw new ProductStockException(ProductStockException.Reason.INVALID_STOCK_QUANTITY);
        }
    }
}
