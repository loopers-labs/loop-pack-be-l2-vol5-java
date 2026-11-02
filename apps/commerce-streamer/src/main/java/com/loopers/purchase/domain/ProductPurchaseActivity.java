package com.loopers.purchase.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;

import java.time.ZonedDateTime;

/**
 * 상품 구매 활동 — 확정 주문의 상품별 구매 수량·확정 시각 (W6 D-6). 9주차 주간 랭킹의 입력이다.
 * 이벤트 값을 그대로 보존한다. 상품 테이블을 조회·갱신하지 않으므로 삭제·비노출 상품을 다시 드러내지 않는다.
 */
@Entity
@Table(
    name = "product_purchase_activity",
    uniqueConstraints = @UniqueConstraint(name = "uk_purchase_activity_order_product", columnNames = {"order_id", "product_id"}),
    indexes = @Index(name = "idx_purchase_activity_product_confirmed", columnList = "product_id, confirmed_at")
)
public class ProductPurchaseActivity extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    @Comment("과거 주문의 상품 ID — 현재 상품 행의 존재·가격과 무관하게 보존")
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false)
    @Comment("주문의 최초 확정 시각 (이벤트 값)")
    private ZonedDateTime confirmedAt;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    protected ProductPurchaseActivity() {}

    private ProductPurchaseActivity(Long orderId, Long productId, int quantity, ZonedDateTime confirmedAt, String eventId) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.confirmedAt = confirmedAt;
        this.eventId = eventId;
    }

    public static ProductPurchaseActivity of(OrderConfirmedMessage message, OrderConfirmedMessage.Item item) {
        return new ProductPurchaseActivity(message.orderId(), item.productId(), item.quantity(), ZonedDateTime.parse(message.confirmedAt()), message.eventId());
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

    public String getEventId() {
        return eventId;
    }
}
