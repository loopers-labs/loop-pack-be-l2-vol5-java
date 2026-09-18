package com.loopers.product.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Embeddable;

@Embeddable
public class Stock {

    private int quantity;

    protected Stock() {
    }

    public Stock(int quantity) {
        if (quantity < 0) {
            throw new CoreException(ErrorCode.INVALID_STOCK_QUANTITY);
        }
        this.quantity = quantity;
    }

    public int quantity() {
        return quantity;
    }

    public Stock decrease(int amount) {
        ensurePositive(amount);
        ensureSufficient(amount);
        return new Stock(quantity - amount);
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
