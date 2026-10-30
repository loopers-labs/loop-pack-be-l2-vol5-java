package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 반영을 끝낸 확정 주문 이벤트의 기록. {@code eventId}와 {@code orderId}의 DB 유일 제약이 동시 소비·재전달의 중복을 막는다.
 * 같은 DB 트랜잭션에서 상품별 구매 활동과 함께 commit되므로, 이 행이 있으면 모든 품목이 저장돼 있다.
 */
@Entity
@Table(
    name = "processed_order_confirmation",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_processed_order_confirmation_event", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_processed_order_confirmation_order", columnNames = "order_id")
    }
)
public class ProcessedOrderConfirmation extends BaseEntity {

    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    // 정규화한 사실의 SHA-256. 같은 eventId가 다른 사실로 다시 오는지 비교한다
    @Column(name = "fingerprint", nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    protected ProcessedOrderConfirmation() {
    }

    public ProcessedOrderConfirmation(OrderConfirmation confirmation) {
        this.eventId = confirmation.eventId();
        this.orderId = confirmation.orderId();
        this.fingerprint = confirmation.fingerprint();
        this.confirmedAt = confirmation.confirmedAt();
    }

    public String getEventId() {
        return eventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
