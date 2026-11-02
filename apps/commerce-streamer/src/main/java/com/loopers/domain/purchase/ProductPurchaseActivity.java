package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

/**
 * 확정된 주문의 상품 한 줄 = 구매 활동 한 행(docs/week6/design.md 6번). 한 확정 사실은 (주문, 상품)마다 한 행만 만든다(INV-605) —
 * 조회 후 insert가 아니라 DB unique가 지킨다. 좀비 consumer와 경합해도 두 행이 생기지 않는다.
 *
 * productId는 과거 사실의 식별자로 보존한다. 상품 행의 존재나 현재 가격을 조회하지 않는다.
 */
@Entity
@Table(
    name = "product_purchase_activity",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_purchase_activity_order_product", columnNames = {"order_id", "product_id"}),
    indexes = @Index(name = "idx_product_purchase_activity_product_id", columnList = "product_id")
)
public class ProductPurchaseActivity extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false)
    private ZonedDateTime confirmedAt;

    protected ProductPurchaseActivity() {}

    public ProductPurchaseActivity(Long orderId, OrderConfirmation.Item item, ZonedDateTime confirmedAt) {
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

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }
}
