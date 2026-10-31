package com.loopers.infrastructure.purchase;

import com.loopers.domain.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
    name = "purchase_activity",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_activity_order_product", columnNames = {"order_id", "product_id"}
    )
)
class PurchaseActivityEntity extends AuditEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    protected PurchaseActivityEntity() {}

    static PurchaseActivityEntity of(Long orderId, Long productId, int quantity, Instant confirmedAt) {
        PurchaseActivityEntity entity = new PurchaseActivityEntity();
        entity.orderId = orderId;
        entity.productId = productId;
        entity.quantity = quantity;
        entity.confirmedAt = confirmedAt;
        return entity;
    }
}
