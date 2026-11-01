package com.loopers.purchase.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
    name = "processed_order_confirmation",
    uniqueConstraints = @UniqueConstraint(name = "uk_processed_confirmation_order_id", columnNames = "order_id")
)
public class ProcessedOrderConfirmation {

    @Id
    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "confirmed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant confirmedAt;

    protected ProcessedOrderConfirmation() {
    }

    public ProcessedOrderConfirmation(String eventId, Long orderId, Instant confirmedAt) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.confirmedAt = confirmedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
