package com.loopers.purchaseactivity.infrastructure;

import com.loopers.purchaseactivity.domain.ProcessedOrderConfirmation;

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
        @UniqueConstraint(name = "uk_confirmation_event", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_confirmation_order", columnNames = "order_id")
})
public class ProcessedOrderConfirmationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "confirmed_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant confirmedAt;

    @Column(name = "items_signature", nullable = false, columnDefinition = "text")
    private String itemsSignature;

    protected ProcessedOrderConfirmationEntity() {
    }

    public ProcessedOrderConfirmationEntity(ProcessedOrderConfirmation confirmation) {
        eventId = confirmation.eventId();
        orderId = confirmation.orderId();
        confirmedAt = confirmation.confirmedAt();
        itemsSignature = confirmation.itemsSignature();
    }

    public ProcessedOrderConfirmation toDomain() {
        return new ProcessedOrderConfirmation(eventId, orderId, confirmedAt, itemsSignature);
    }
}
