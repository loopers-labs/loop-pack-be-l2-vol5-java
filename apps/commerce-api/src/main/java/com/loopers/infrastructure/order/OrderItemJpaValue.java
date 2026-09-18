package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderItem;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class OrderItemJpaValue {
    @Column(nullable = false) private long productId;
    @Column(nullable = false) private int quantity;
    @Column(nullable = false) private long unitPrice;
    @Column(nullable = false) private long lineAmount;
    protected OrderItemJpaValue() {}
    public OrderItemJpaValue(OrderItem item) {
        productId = item.getProductId();
        quantity = item.getQuantity();
        unitPrice = item.getUnitPrice();
        lineAmount = item.getLineAmount();
    }
    public OrderItem toDomain() { return OrderItem.restore(productId, quantity, unitPrice, lineAmount); }
}
