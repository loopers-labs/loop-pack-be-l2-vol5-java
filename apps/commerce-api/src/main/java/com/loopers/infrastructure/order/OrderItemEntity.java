package com.loopers.infrastructure.order;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.product.Price;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "order_item",
    indexes = @Index(name = "idx_order_item_order_id", columnList = "order_id")
)
class OrderItemEntity extends AuditEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false, updatable = false, length = 100)
    private String productName;

    @Column(name = "unit_price", nullable = false, updatable = false)
    private long unitPrice;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    protected OrderItemEntity() {}

    static OrderItemEntity of(Long orderId, OrderItem item) {
        OrderItemEntity entity = new OrderItemEntity();
        entity.orderId = orderId;
        entity.productId = item.productId();
        entity.productName = item.productName();
        entity.unitPrice = item.unitPrice().value();
        entity.quantity = item.quantity().value();
        return entity;
    }

    Long getOrderId() {
        return orderId;
    }

    OrderItem toDomain() {
        return OrderItem.of(productId, productName, Price.of(unitPrice), OrderQuantity.of(quantity));
    }
}
