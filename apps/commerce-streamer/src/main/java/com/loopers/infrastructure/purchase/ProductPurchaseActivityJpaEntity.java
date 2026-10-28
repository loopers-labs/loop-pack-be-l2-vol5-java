package com.loopers.infrastructure.purchase;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "product_purchase_activity", uniqueConstraints =
    @UniqueConstraint(name = "uk_purchase_activity_order_product", columnNames = {"order_id", "product_id"}))
public class ProductPurchaseActivityJpaEntity extends BaseEntity {
    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant confirmedAt;

    protected ProductPurchaseActivityJpaEntity() {}

    private ProductPurchaseActivityJpaEntity(ProductPurchaseActivity activity) {
        orderId = activity.orderId();
        productId = activity.productId();
        quantity = activity.quantity();
        confirmedAt = activity.confirmedAt();
    }

    public static ProductPurchaseActivityJpaEntity create(ProductPurchaseActivity activity) {
        return new ProductPurchaseActivityJpaEntity(activity);
    }

    public ProductPurchaseActivity toDomain() {
        return ProductPurchaseActivity.create(orderId, productId, quantity, confirmedAt);
    }
}
