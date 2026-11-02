package com.loopers.domain.activity;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 소비 완료한 주문 확정 사실 (중복 방지 기록). 구매 활동 행들과 같은 트랜잭션에서 저장한다.
 * eventId·orderId 각각 유일: 같은 사실의 재전달도, 같은 주문을 다른 eventId 로 다시 보낸 것도 새 구매가 되지 않는다.
 * 고정 payload 는 fingerprint 로 비교한다.
 */
@Entity
@Table(name = "processed_order_confirmation", uniqueConstraints = {
    @UniqueConstraint(name = "ux_processed_order_confirmation_event", columnNames = "event_id"),
    @UniqueConstraint(name = "ux_processed_order_confirmation_order", columnNames = "order_id")
})
public class ProcessedOrderConfirmationModel extends BaseEntity {

    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private ZonedDateTime confirmedAt;

    @Column(name = "fingerprint", nullable = false, updatable = false, length = 64)
    private String fingerprint;

    protected ProcessedOrderConfirmationModel() {}

    public ProcessedOrderConfirmationModel(OrderConfirmation confirmation) {
        this.eventId = confirmation.eventId();
        this.orderId = confirmation.orderId();
        this.confirmedAt = ZonedDateTime.ofInstant(confirmation.confirmedAt(), ZoneId.systemDefault());
        this.fingerprint = confirmation.fingerprint();
    }

    /** 같은 eventId·orderId·고정 payload 인지. */
    public boolean isSameFactAs(OrderConfirmation confirmation) {
        return eventId.equals(confirmation.eventId())
            && orderId.equals(confirmation.orderId())
            && fingerprint.equals(confirmation.fingerprint());
    }

    public String getEventId() {
        return eventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public String getFingerprint() {
        return fingerprint;
    }
}
