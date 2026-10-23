package com.loopers.domain.product;

import jakarta.persistence.Embeddable;

@Embeddable
public class Stock {

    private int quantity;

    protected Stock() {}

    public Stock(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("재고 수량은 0 이상이어야 합니다.");
        }
        this.quantity = quantity;
    }

    public int getQuantity() {
        return quantity;
    }

    public Stock decrease(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("차감 수량은 양수여야 합니다.");
        }
        if (quantity > this.quantity) {
            throw new IllegalArgumentException("재고가 부족합니다.");
        }
        return new Stock(this.quantity - quantity);
    }

    public Stock increase(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("반환 수량은 양수여야 합니다.");
        }
        return new Stock(Math.addExact(this.quantity, quantity));
    }

    public Stock changeTo(int quantity) {
        return new Stock(quantity);
    }
}
