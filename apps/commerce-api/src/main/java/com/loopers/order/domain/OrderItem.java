package com.loopers.order.domain;

import com.loopers.common.domain.Money;
import com.loopers.product.domain.ProductId;
import java.util.Objects;

public record OrderItem(ProductId productId, Quantity quantity, Money unitPrice) {
    public OrderItem {
        Objects.requireNonNull(productId);
        Objects.requireNonNull(quantity);
        Objects.requireNonNull(unitPrice);
    }

    public Money subtotal() {
        return unitPrice.multiply(quantity.value());
    }
}
