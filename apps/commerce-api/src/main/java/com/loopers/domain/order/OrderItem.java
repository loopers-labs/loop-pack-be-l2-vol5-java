package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.product.Price;

public record OrderItem(Long productId, String productName, Price unitPrice, OrderQuantity quantity) {

    public OrderItem {
        if (productId == null) {
            throw new IllegalArgumentException("productId 는 필수입니다");
        }
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName 은 필수입니다");
        }
        if (unitPrice == null) {
            throw new IllegalArgumentException("unitPrice 는 필수입니다");
        }
        if (quantity == null) {
            throw new IllegalArgumentException("quantity 는 필수입니다");
        }
    }

    public static OrderItem of(Long productId, String productName, Price unitPrice, OrderQuantity quantity) {
        return new OrderItem(productId, productName, unitPrice, quantity);
    }

    OrderItem mergeWith(OrderItem other) {
        return new OrderItem(productId, productName, unitPrice, quantity.plus(other.quantity));
    }

    public Money lineTotal() {
        return unitPrice.times(quantity.toQuantity());
    }
}
