package com.loopers.domains.order.domain;

import com.loopers.common.error.InvalidValueException;

public record Quantity(int value) {
    public Quantity {
        if (value <= 0) { throw new InvalidValueException("주문 수량은 양수여야 합니다."); }
    }
}
