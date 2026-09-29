package com.loopers.domain.order;

import com.loopers.domain.common.Quantity;

public record OrderQuantity(int value) {

    public OrderQuantity {
        if (value <= 0) {
            throw new IllegalArgumentException("주문 수량은 1 이상이어야 합니다: " + value);
        }
    }

    public static OrderQuantity of(int value) {
        return new OrderQuantity(value);
    }

    public OrderQuantity plus(OrderQuantity other) {
        return new OrderQuantity(Math.addExact(value, other.value));
    }

    public Quantity toQuantity() {
        return Quantity.of(value);
    }
}
