package com.loopers.domain.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

@Entity
@Table(name = "processed_order_confirmation", uniqueConstraints = {
    @UniqueConstraint(name = "uq_poc_event_id", columnNames = "event_id"),
    @UniqueConstraint(name = "uq_poc_order_id", columnNames = "order_id")
})
public class ProcessedOrderConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "payload_fingerprint", nullable = false)
    private String payloadFingerprint;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    protected ProcessedOrderConfirmation() {
    }

    public ProcessedOrderConfirmation(String eventId, Long orderId, String payloadFingerprint) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.payloadFingerprint = payloadFingerprint;
        this.createdAt = ZonedDateTime.now();
    }

    public String getEventId() { return eventId; }
    public Long getOrderId() { return orderId; }
    public String getPayloadFingerprint() { return payloadFingerprint; }
}
