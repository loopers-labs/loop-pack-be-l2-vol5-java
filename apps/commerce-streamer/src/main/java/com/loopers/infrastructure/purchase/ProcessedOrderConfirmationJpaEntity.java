package com.loopers.infrastructure.purchase;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "processed_order_confirmation", uniqueConstraints = {
    @UniqueConstraint(name = "uk_processed_confirmation_event", columnNames = "event_id"),
    @UniqueConstraint(name = "uk_processed_confirmation_order", columnNames = "order_id")
})
public class ProcessedOrderConfirmationJpaEntity extends BaseEntity {
    @Column(name = "event_id", nullable = false, columnDefinition = "varchar(255) COLLATE utf8mb4_0900_bin")
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
