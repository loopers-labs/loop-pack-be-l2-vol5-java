package com.loopers.infrastructure.purchase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "processed_order_confirmation", uniqueConstraints = {
    @UniqueConstraint(name = "uk_processed_order_confirmation_event_id", columnNames = "event_id"),
    @UniqueConstraint(name = "uk_processed_order_confirmation_order_id", columnNames = "order_id")
})
public class ProcessedOrderConfirmationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String eventId;

    @Column(nullable = false, updatable = false)
    private long orderId;

    @Column(nullable = false, updatable = false, length = 64)
    private String payloadFingerprint;

    @Column(nullable = false, updatable = false)
    private Instant confirmedAt;

    @Column(nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedOrderConfirmationJpaEntity() {
    }

    ProcessedOrderConfirmationJpaEntity(
        String eventId,
        long orderId,
        String payloadFingerprint,
        Instant confirmedAt,
        Instant processedAt
    ) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.payloadFingerprint = payloadFingerprint;
        this.confirmedAt = confirmedAt;
        this.processedAt = processedAt;
    }

    String getEventId() {
        return eventId;
    }

    long getOrderId() {
        return orderId;
    }

    String getPayloadFingerprint() {
        return payloadFingerprint;
    }
}
