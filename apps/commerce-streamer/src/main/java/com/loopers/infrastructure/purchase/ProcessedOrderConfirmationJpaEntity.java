package com.loopers.infrastructure.purchase;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "processed_order_confirmation", uniqueConstraints = {
    @UniqueConstraint(name = ProcessedOrderConfirmationJpaEntity.EVENT_UNIQUE_CONSTRAINT, columnNames = "event_id"),
    @UniqueConstraint(name = ProcessedOrderConfirmationJpaEntity.ORDER_UNIQUE_CONSTRAINT, columnNames = "order_id")
})
public class ProcessedOrderConfirmationJpaEntity extends BaseEntity {
    static final String EVENT_UNIQUE_CONSTRAINT = "uk_processed_confirmation_event";
    static final String ORDER_UNIQUE_CONSTRAINT = "uk_processed_confirmation_order";

    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private long orderId;

    protected ProcessedOrderConfirmationJpaEntity() {}

    private ProcessedOrderConfirmationJpaEntity(ProcessedOrderConfirmation confirmation) {
        eventId = confirmation.eventId();
        orderId = confirmation.orderId();
    }

    public static ProcessedOrderConfirmationJpaEntity create(ProcessedOrderConfirmation confirmation) {
        return new ProcessedOrderConfirmationJpaEntity(confirmation);
    }

    public ProcessedOrderConfirmation toDomain() {
        return ProcessedOrderConfirmation.create(eventId, orderId);
    }
}
