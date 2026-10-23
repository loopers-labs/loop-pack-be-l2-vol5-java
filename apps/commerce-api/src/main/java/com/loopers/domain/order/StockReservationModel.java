package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 주문 하나를 위해 확보한 재고 (design.md 9-2절). 확보·반환할 수량은 바뀌지 않는 주문 품목에서 읽는다.
 * 확정 실패의 재고 반환은 {@code RESERVED → RELEASED} 전이가 일어난 경우에만 한다. 같은 실패 알림이 두 번 와도 한 번만 되돌린다.
 */
@Entity
@Table(name = "stock_reservation", uniqueConstraints = @UniqueConstraint(name = "uk_stock_reservation_order", columnNames = "order_id"))
public class StockReservationModel extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status;

    protected StockReservationModel() {
    }

    public StockReservationModel(Long orderId) {
        if (orderId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문은 비어있을 수 없습니다.");
        }
        this.orderId = orderId;
        this.status = ReservationStatus.RESERVED;
    }

    /** 확보한 수량을 판매로 마무리한다. 이미 끝난 예약이면 아무것도 바꾸지 않고 false다. */
    public boolean consume() {
        if (status != ReservationStatus.RESERVED) {
            return false;
        }
        status = ReservationStatus.CONSUMED;
        return true;
    }

    /** 확보한 수량을 되돌리기로 한다. 이번 호출에서 처음 반환하면 true이고, 그때만 재고를 늘려야 한다. */
    public boolean release() {
        if (status != ReservationStatus.RESERVED) {
            return false;
        }
        status = ReservationStatus.RELEASED;
        return true;
    }

    public Long getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }
}
