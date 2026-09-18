package com.loopers.infrastructure.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class OrderJpaEntity extends BaseEntity {
    @Column(nullable = false) private long userId;
    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "item_position")
    private List<OrderItemJpaValue> items = new ArrayList<>();
    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private OrderStatus status;
    @Column(nullable = false) private long totalAmount;
    private Long paidAmount;
    private String paymentResult;

    protected OrderJpaEntity() {}
    public OrderJpaEntity(Order order) { update(order); }
    public void update(Order order) {
        userId = order.getUserId();
        items.clear();
        order.getItems().stream().map(OrderItemJpaValue::new).forEach(items::add);
        totalAmount = order.getTotalAmount();
        status = order.getStatus();
        paidAmount = order.getPaidAmount();
        paymentResult = order.getPaymentResult();
    }
    public Order toDomain() {
        return Order.restore(getId(), userId, items.stream().map(OrderItemJpaValue::toDomain).toList(),
            totalAmount, status, paidAmount, paymentResult);
    }
}
