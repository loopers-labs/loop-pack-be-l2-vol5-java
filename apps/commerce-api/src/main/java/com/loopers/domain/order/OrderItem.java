package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.product.ProductId;
import java.util.Objects;

public record OrderItem(ProductId productId, Quantity quantity, Money unitPrice) {
    public OrderItem {
        Objects.requireNonNull(productId);
        Objects.requireNonNull(quantity);
        Objects.requireNonNull(unitPrice);
    }
    public Money subtotal() { return unitPrice.multiply(quantity.value()); }
}
