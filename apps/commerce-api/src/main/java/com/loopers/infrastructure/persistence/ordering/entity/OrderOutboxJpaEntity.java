package com.loopers.infrastructure.persistence.ordering.entity;

import com.loopers.application.ordering.event.OrderOutboxEntry;
import com.loopers.application.ordering.gateway.OrderOutboxRepository.Candidate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_event_outbox", indexes = @Index(columnList = "status, next_attempt_at, event_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderOutboxJpaEntity {
    @Id
    @Column(columnDefinition = "CHAR(36)")
    private String eventId;
    @Column(nullable = false)
    private long orderId;
    @Column(nullable = false, length = 40)
    private String eventType;
    @Column(nullable = false)
    private int schemaVersion;
    @Column(nullable = false, length = 249)
    private String destinationTopic;
    @Column(nullable = false, length = 40)
    private String messageKey;
    @Lob
    @Column(nullable = false, columnDefinition = "LONGBLOB")
    private byte[] payload;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false, length = 12)
    private String status;
    @Column(nullable = false)
    private int attempts;
    @Column(nullable = false)
    private Instant nextAttemptAt;
    private Instant publishedAt;
    @Column(length = 249)
    private String publishedTopic;
    private Integer publishedPartition;
    private Long publishedOffset;
    @Column(length = 80)
    private String lastError;

    public OrderOutboxJpaEntity(OrderOutboxEntry entry) {
        eventId = entry.eventId().toString();
        orderId = entry.orderId();
        eventType = entry.eventType();
        schemaVersion = entry.schemaVersion();
        destinationTopic = entry.topic();
        messageKey = entry.key();
        payload = entry.payload();
        createdAt = entry.createdAt();
        status = "PENDING";
        nextAttemptAt = createdAt;
    }

    public Candidate candidate() {
        return new Candidate(UUID.fromString(eventId), destinationTopic, messageKey, payload, attempts);
    }
}
