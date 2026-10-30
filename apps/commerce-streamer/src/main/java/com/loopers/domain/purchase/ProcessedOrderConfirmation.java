package com.loopers.domain.purchase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Its committed existence means all purchase activity rows were committed in the same transaction. */
@Entity
@Table(name = "processed_order_confirmation", uniqueConstraints = {
    @UniqueConstraint(name = "uk_processed_order_confirmation_event_id", columnNames = "event_id"),
    @UniqueConstraint(name = "uk_processed_order_confirmation_order_id", columnNames = "order_id")
})
public class ProcessedOrderConfirmation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false,
        columnDefinition = "varchar(255) collate utf8mb4_0900_bin")
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private long orderId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 40)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "confirmed_at_second", nullable = false, updatable = false)
    private long confirmedAtSecond;

    @Column(name = "confirmed_at_nano", nullable = false, updatable = false)
    private int confirmedAtNano;

    protected ProcessedOrderConfirmation() {
    }

    public ProcessedOrderConfirmation(OrderConfirmation confirmation) {
        Objects.requireNonNull(confirmation, "confirmation");
        eventId = confirmation.eventId();
        orderId = confirmation.orderId();
        eventType = confirmation.eventType();
        schemaVersion = confirmation.schemaVersion();
        confirmedAtSecond = confirmation.confirmedAt().getEpochSecond();
        confirmedAtNano = confirmation.confirmedAt().getNano();
    }

    public long orderId() {
        return orderId;
    }

    public OrderConfirmation snapshot(List<ProductPurchaseActivity> activities) {
        Instant confirmedAt = Instant.ofEpochSecond(confirmedAtSecond, confirmedAtNano);
        for (ProductPurchaseActivity activity : activities) {
            if (activity.orderId() != orderId || !activity.confirmedAt().equals(confirmedAt)) {
                throw new IllegalStateException("Stored purchase facts are inconsistent");
            }
        }
        return new OrderConfirmation(eventType, schemaVersion, eventId, orderId, confirmedAt,
            activities.stream().map(ProductPurchaseActivity::item).toList());
    }
}
