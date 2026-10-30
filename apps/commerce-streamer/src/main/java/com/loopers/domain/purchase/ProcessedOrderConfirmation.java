package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/** 소비를 마친 주문 확정 이벤트. eventId 와 orderId 가 각각 유일해 같은 사실이 두 번 반영되지 않게 한다. */
@Getter
@Entity
@Table(
    name = "processed_order_confirmation",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_processed_order_confirmation_event_id", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_processed_order_confirmation_order_id", columnNames = "order_id")
    }
)
public class ProcessedOrderConfirmation extends BaseEntity {

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "confirmed_at", nullable = false)
    private ZonedDateTime confirmedAt;

    // JPA 가 사용하는 기본 생성자.
    protected ProcessedOrderConfirmation() {}

    public ProcessedOrderConfirmation(String eventId, Long orderId, ZonedDateTime confirmedAt) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.confirmedAt = confirmedAt;
    }
}
