package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

@Getter
@Entity
@Table(name = "processed_order_confirmation", uniqueConstraints = {
    @UniqueConstraint(name = ProcessedOrderConfirmation.EVENT_CONSTRAINT, columnNames = "event_id"),
    @UniqueConstraint(name = ProcessedOrderConfirmation.ORDER_CONSTRAINT, columnNames = "order_id")
})
public class ProcessedOrderConfirmation extends BaseEntity {
    public static final String EVENT_CONSTRAINT = "uk_processed_confirmation_event";
    public static final String ORDER_CONSTRAINT = "uk_processed_confirmation_order";

    @Column(name = "event_id", nullable = false, updatable = false, length = 255)
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "fact_snapshot", nullable = false, updatable = false, columnDefinition = "longtext")
    private String factSnapshot;

    protected ProcessedOrderConfirmation() {}

    public ProcessedOrderConfirmation(OrderPurchase purchase) {
        this.eventId = purchase.eventId();
        this.orderId = purchase.orderId();
        this.factSnapshot = purchase.factSnapshot();
    }

    public void verifySameFact(OrderPurchase purchase) {
        if (!eventId.equals(purchase.eventId()) || !orderId.equals(purchase.orderId())
            || !factSnapshot.equals(purchase.factSnapshot())) {
            throw new OrderConfirmationConflictException(purchase.eventId(), purchase.orderId());
        }
    }
}
