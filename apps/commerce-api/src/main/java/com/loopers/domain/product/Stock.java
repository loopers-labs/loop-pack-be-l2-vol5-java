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

    // 확보했다가 결제가 확정 실패한 수량을 되돌린다. 반환이 한 번만 일어나게 하는 것은 재고 예약의 상태 전이가 맡는다
    public void increase(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "되돌릴 수 없는 수량입니다.");
        }
        remaining = Math.addExact(remaining, quantity);
    }

    public int getRemaining() {
        return remaining;
    }
}
