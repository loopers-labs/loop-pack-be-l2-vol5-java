package com.loopers.infrastructure.purchase;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.purchase.ConfirmedPurchase;
import com.loopers.domain.purchase.RecordedConfirmation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
    name = "order_confirmation_receipt",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_receipt_event", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_receipt_order", columnNames = "order_id")
    }
)
class OrderConfirmationReceiptEntity extends AuditEntity {

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    @Column(name = "items_digest", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String itemsDigest;

    protected OrderConfirmationReceiptEntity() {}

    static OrderConfirmationReceiptEntity of(ConfirmedPurchase purchase) {
        OrderConfirmationReceiptEntity entity = new OrderConfirmationReceiptEntity();
        entity.eventId = purchase.eventId();
        entity.orderId = purchase.orderId();
        entity.confirmedAt = purchase.confirmedAt();
        entity.itemsDigest = purchase.itemsDigest();
        return entity;
    }

    RecordedConfirmation toRecorded() {
        return new RecordedConfirmation(eventId, orderId, confirmedAt, itemsDigest);
    }
}
