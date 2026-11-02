package com.loopers.order.out;

import jakarta.persistence.Embeddable;

@Embeddable
public class OrderItemJpaValue {
    long productId;
    int quantity;
    long unitPrice;
    protected OrderItemJpaValue() { }
    OrderItemJpaValue(long productId, int quantity, long unitPrice) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }
}
