package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 주문이 재고에서 확보해 둔 수량과 그 상태. 재고 쪽 기록이며 주문 품목의 복사가 아니다 (설계 1-4).
 * RESERVED → CONSUMED(성공, 추가 차감 없음) / RELEASED(실패, 재고 1회 복원). 전이는 Repository 의 조건부 UPDATE 가 맡는다.
 */
@Getter
@Entity
@Table(name = "stock_reservations")
public class StockReservation extends BaseEntity {

    public enum Status {
        RESERVED, CONSUMED, RELEASED
    }

    private Long orderId;

    private Long productId;

    private int quantity;

    @Enumerated(EnumType.STRING)
    private Status status;

    protected StockReservation() {}

    public StockReservation(Long orderId, Long productId, int quantity) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = Status.RESERVED;
    }
}