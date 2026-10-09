package com.loopers.product.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Table;

@Entity
@Table(name = "stock")
public class Stock extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long productId;

    private int quantity;

    protected Stock() {
    }

    public Stock(int quantity) {
        this(null, quantity);
    }

    public Stock(Long productId, int quantity) {
        if (quantity < 0) {
            throw new CoreException(ErrorCode.INVALID_STOCK_QUANTITY);
        }
        this.productId = productId;
        this.quantity = quantity;
    }

    public Long getProductId() { return productId; }

    public int quantity() {
        return quantity;
    }

    public Stock decrease(int amount) {
        ensurePositive(amount);
        ensureSufficient(amount);
        return new Stock(productId, quantity - amount);
    }

    public void changeQuantity(int quantity) {
        if (quantity < 0) {
            throw new CoreException(ErrorCode.INVALID_STOCK_QUANTITY);
        }
        this.quantity = quantity;
    }

    private static void ensurePositive(int amount) {
        if (amount <= 0) {
            throw new CoreException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void ensureSufficient(int amount) {
        if (amount > quantity) {
            throw new CoreException(ErrorCode.INSUFFICIENT_STOCK);
        }
    }
}
