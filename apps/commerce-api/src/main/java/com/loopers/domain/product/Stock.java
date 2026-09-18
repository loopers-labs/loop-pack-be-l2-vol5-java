package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class Stock {

    @Column(name = "stock")
    private int remaining;

    protected Stock() {
    }

    public Stock(int remaining) {
        if (remaining < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "초기 재고는 음수일 수 없습니다.");
        }
        this.remaining = remaining;
    }

    public void decrease(int quantity) {
        if (quantity <= 0 || quantity > remaining) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감할 수 없는 수량입니다.");
        }
        remaining -= quantity;
    }

    public int getRemaining() {
        return remaining;
    }
}
