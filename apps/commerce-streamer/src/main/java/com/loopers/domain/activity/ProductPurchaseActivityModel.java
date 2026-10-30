package com.loopers.domain.activity;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 상품 구매 활동: 확정 주문 하나의 상품별 구매 수량과 확정 시각. 주간 랭킹의 입력이 된다.
 * (order_id, product_id) 당 하나. productId 는 과거 주문의 식별자로 보존하며 현재 상품 행의 존재·가격에 의존하지 않는다.
 */
@Entity
@Table(name = "product_purchase_activity", uniqueConstraints =
    @UniqueConstraint(name = "ux_product_purchase_activity_order_product", columnNames = {"order_id", "product_id"}))
public class ProductPurchaseActivityModel extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private Integer quantity;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private ZonedDateTime confirmedAt;

    protected ProductPurchaseActivityModel() {}

    ProductPurchaseActivityModel(OrderConfirmation confirmation, OrderConfirmation.Line line) {
        this.orderId = confirmation.orderId();
        this.productId = line.productId();
        this.quantity = line.quantity();
        this.confirmedAt = ZonedDateTime.ofInstant(confirmation.confirmedAt(), ZoneId.systemDefault());
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getProductId() {
        return productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }
}
