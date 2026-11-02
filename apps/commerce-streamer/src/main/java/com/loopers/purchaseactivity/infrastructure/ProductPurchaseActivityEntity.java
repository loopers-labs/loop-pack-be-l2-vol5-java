package com.loopers.purchaseactivity.infrastructure;

import com.loopers.purchaseactivity.domain.PurchaseActivity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "product_purchase_activity", uniqueConstraints =
        @UniqueConstraint(name = "uk_purchase_activity_order_product", columnNames = {"order_id", "product_id"}))
public class ProductPurchaseActivityEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant confirmedAt;

    protected ProductPurchaseActivityEntity() {
    }

    public ProductPurchaseActivityEntity(PurchaseActivity activity) {
        orderId = activity.orderId();
        productId = activity.productId();
        quantity = activity.quantity();
        confirmedAt = activity.confirmedAt();
    }

    public PurchaseActivity toDomain() {
        return new PurchaseActivity(orderId, productId, quantity, confirmedAt);
    }
}
