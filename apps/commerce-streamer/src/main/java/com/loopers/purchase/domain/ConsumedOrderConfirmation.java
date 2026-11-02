package com.loopers.purchase.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;

/**
 * 소비 완료 기록 (W6 D-6, Inbox 역할). 구매 활동 품목과 같은 트랜잭션에서 저장한다.
 * eventId·orderId 유일 제약이 동시 처리에서도 한 번만 통과시킨다 (ADR-W6-05).
 */
@Entity
@Table(
    name = "consumed_order_confirmation",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_consumed_confirmation_event", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_consumed_confirmation_order", columnNames = "order_id")
    }
)
public class ConsumedOrderConfirmation extends BaseEntity {

    @Column(name = "event_id", nullable = false, length = 36)
    @Comment("같은 이벤트를 두 번 처리하지 않는다")
    private String eventId;

    @Column(name = "order_id", nullable = false)
    @Comment("같은 주문에 다른 이벤트를 받지 않는다")
    private Long orderId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    @Comment("정규화한 사실(주문·확정 순간·상품별 수량)의 SHA-256")
    private String fingerprint;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    @Comment("받은 원문 — 충돌 조사용")
    private String payload;

    protected ConsumedOrderConfirmation() {}

    private ConsumedOrderConfirmation(String eventId, Long orderId, String fingerprint, String payload) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.fingerprint = fingerprint;
        this.payload = payload;
    }

    public static ConsumedOrderConfirmation of(OrderConfirmedMessage message, String payload) {
        return new ConsumedOrderConfirmation(message.eventId(), message.orderId(), message.fingerprint(), payload);
    }

    /**
     * 이미 있는 이 기록 앞에 같은 eventId 또는 같은 주문의 이벤트가 왔다.
     */
    public ConsumptionVerdict judge(OrderConfirmedMessage incoming) {
        if (!eventId.equals(incoming.eventId())) {
            return ConsumptionVerdict.CONFLICT_SAME_ORDER_DIFFERENT_EVENT;
        }
        return fingerprint.equals(incoming.fingerprint())
            ? ConsumptionVerdict.DUPLICATE
            : ConsumptionVerdict.CONFLICT_SAME_EVENT_DIFFERENT_FACT;
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

    public String getPayload() {
        return payload;
    }
}
