package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

/**
 * 처리한 주문 확정 한 건의 기록(docs/week6/design.md 6번). 그 이벤트의 모든 구매 활동과 같은 트랜잭션에서 함께 남는다(INV-604).
 *
 * - event_id와 order_id에 각각 unique를 둔다(docs/week6/decisions.md D-18). 이 테이블은 주문 확정 전용이고 주문 하나의 확정은
 *   한 번이라 orderId unique가 자연스럽다. 같은 주문의 다른 eventId는 내용이 같아도 conflict다.
 * - 처음 처리한 사실을 정규 형식 문자열로 남긴다(D-7, D-27). 같은 eventId가 다시 오면 이 문자열과 비교한다.
 */
@Entity
@Table(
    name = "processed_order_confirmation",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_processed_order_confirmation_event_id", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_processed_order_confirmation_order_id", columnNames = "order_id")
    }
)
public class ProcessedOrderConfirmation extends BaseEntity {

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "fact", nullable = false, columnDefinition = "text")
    private String fact;

    @Column(name = "processed_at", nullable = false)
    private ZonedDateTime processedAt;

    protected ProcessedOrderConfirmation() {}

    public ProcessedOrderConfirmation(OrderConfirmation confirmation, PurchaseFact fact, ZonedDateTime processedAt) {
        this.eventId = confirmation.eventId();
        this.orderId = confirmation.orderId();
        this.fact = fact.value();
        this.processedAt = processedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public PurchaseFact getFact() {
        return new PurchaseFact(fact);
    }

    public ZonedDateTime getProcessedAt() {
        return processedAt;
    }
}
