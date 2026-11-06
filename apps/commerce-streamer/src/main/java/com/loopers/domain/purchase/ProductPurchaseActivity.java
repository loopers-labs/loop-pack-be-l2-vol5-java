package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 확정 주문의 상품별 구매 활동 한 줄. 주문·상품별로 하나이고, 수량·확정 시각을 그대로 보존한다 (9주차 주간 랭킹의 입력).
 * 상품 행을 참조하지 않는다. 삭제·비노출 상품이어도 과거 주문의 productId는 사실의 식별자로 남는다.
 */
@Entity
@Table(
    name = "product_purchase_activity",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_purchase_activity_order_product", columnNames = {"order_id", "product_id"}),
    indexes = @Index(name = "idx_product_purchase_activity_product", columnList = "product_id, confirmed_at")
)
public class ProductPurchaseActivity extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    protected ProductPurchaseActivity() {
    }

    public ProductPurchaseActivity(Long orderId, OrderConfirmation.Item item, Instant confirmedAt) {
        this.orderId = orderId;
        this.productId = item.productId();
        this.quantity = item.quantity();
        this.confirmedAt = confirmedAt;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
