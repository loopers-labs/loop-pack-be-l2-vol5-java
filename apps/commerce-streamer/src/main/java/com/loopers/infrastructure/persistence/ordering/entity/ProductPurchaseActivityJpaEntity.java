package com.loopers.infrastructure.persistence.ordering.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "product_purchase_activities", uniqueConstraints = @UniqueConstraint(
    name = "uk_purchase_activities_order_product", columnNames = {"order_id", "product_id"}
))
public class ProductPurchaseActivityJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inbox_id", nullable = false, updatable = false,
        foreignKey = @ForeignKey(name = "fk_purchase_activities_inbox"))
    private OrderConfirmationInboxJpaEntity inbox;

    @Column(name = "order_id", nullable = false, updatable = false)
    private long orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private long productId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    protected ProductPurchaseActivityJpaEntity() {}

    public ProductPurchaseActivityJpaEntity(OrderConfirmationInboxJpaEntity inbox, long orderId,
                                            long productId, int quantity, Instant confirmedAt) {
        this.inbox = inbox;
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.confirmedAt = confirmedAt;
    }
}
