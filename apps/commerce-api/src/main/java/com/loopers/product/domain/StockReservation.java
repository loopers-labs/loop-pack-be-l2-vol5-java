package com.loopers.product.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;

/**
 * 주문 하나가 상품 하나의 가용 재고를 얼마나 쥐고 있는지 (ADR-W5-02). 콘서트 좌석 임시 선점과 같다.
 * 선점할 때 상품의 가용 재고를 이미 줄였으므로, 소비는 재고를 바꾸지 않고 반환만 재고를 되돌린다.
 */
@Entity
@Table(
    name = "stock_reservations",
    uniqueConstraints = @UniqueConstraint(name = "uk_stock_reservations_order_product", columnNames = {"order_id", "product_id"}),
    indexes = @Index(name = "idx_stock_reservations_order", columnList = "order_id")
)
public class StockReservation extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    @Comment("선점한 주문 식별자 (ADR-W5-02)")
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    @Comment("선점한 상품 식별자. order_id와 쌍으로 unique (ADR-W5-02)")
    private Long productId;

    @Column(name = "quantity", nullable = false)
    @Comment("선점 수량, 1 이상 (ADR-W5-02)")
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Comment("RESERVED → CONSUMED | RELEASED (ADR-W5-02)")
    private StockReservationStatus status;

    protected StockReservation() {}

    public StockReservation(Long orderId, Long productId, int quantity) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = StockReservationStatus.RESERVED;
    }

    public boolean consume() {
        return false;
    }

    public boolean release() {
        return false;
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

    public StockReservationStatus getStatus() {
        return status;
    }
}
