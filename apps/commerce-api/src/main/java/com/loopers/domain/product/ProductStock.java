package com.loopers.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 상품이 소유하는 재고 수량과 최종 수량 설정·차감 규칙을 관리한다.
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

    private static void validateNonNegativeQuantity(int quantity) {
        if (quantity < 0) {
            throw new ProductStockException(ProductStockException.Reason.INVALID_STOCK_QUANTITY);
        }
    }
}
