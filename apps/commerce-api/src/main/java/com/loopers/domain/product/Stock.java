package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Embeddable;

@Embeddable
public class Stock {

    private int remaining;

    protected Stock() {}

    public Stock(int remaining) {
        if (remaining < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "초기 재고는 음수일 수 없습니다.");
        }
        this.remaining = remaining;
    }

    public void decrease(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감할 수량은 0보다 커야 합니다.");
        }
        if (quantity > remaining) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고보다 많은 수량을 차감할 수 없습니다.");
        }
        remaining -= quantity;
    }

    /**
     * 최종 수량을 절대값으로 설정한다 (관리자 재고 변경 API 전용).
     * decrease()는 상대 차감(주문 확정), set()은 절대 지정(관리자 조정)이라 서로 다른 동작이다.
     */
    public void set(int quantity) {
        if (quantity < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고 수량은 음수일 수 없습니다.");
        }
        this.remaining = quantity;
    }

    public int remaining() {
        return remaining;
    }
}
