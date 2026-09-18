package com.loopers.infrastructure.order;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.common.Money;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

@Entity
@Table(
    name = "orders",
    indexes = @Index(name = "idx_orders_user_id", columnList = "user_id, id")
)
class OrderEntity extends AuditEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "total_amount", nullable = false, updatable = false)
    private long totalAmount;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "paid_amount")
    private Long paidAmount;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected OrderEntity() {}

    static OrderEntity from(Order order) {
        OrderEntity entity = new OrderEntity();
        entity.userId = order.getUserId();
        entity.totalAmount = order.getTotalAmount().amount();
        entity.placedAt = order.getPlacedAt();
        entity.status = order.getStatus();
        entity.paidAmount = order.getPaidAmount() != null ? order.getPaidAmount().amount() : null;
        entity.confirmedAt = order.getConfirmedAt();
        return entity;
    }

    void applyConfirmation(Order order) {
        this.status = order.getStatus();
        this.paidAmount = order.getPaidAmount() != null ? order.getPaidAmount().amount() : null;
        this.confirmedAt = order.getConfirmedAt();
    }

    Order toDomain(List<OrderItem> items) {
        return Order.restore(
            getId(), userId, items, Money.of(totalAmount), placedAt, status,
            paidAmount != null ? Money.of(paidAmount) : null, confirmedAt);
    }

    Long getUserId() {
        return userId;
    }
}
