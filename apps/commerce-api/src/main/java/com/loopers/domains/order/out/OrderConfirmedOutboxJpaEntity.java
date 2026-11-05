package com.loopers.domains.order.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

// relay는 status = PENDING이고 next_attempt_at이 지난 행을 next_attempt_at, id 순으로 고른다. 인덱스는 그 조건과 정렬을 덮는다.
@Entity
@Table(name = "order_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_order_outbox_event_id", columnNames = "event_id"),
    indexes = @Index(name = "idx_order_outbox_status_next_attempt", columnList = "status, next_attempt_at, id"))
public class OrderConfirmedOutboxJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false, length = 100)
    private String eventId;
    @Column(nullable = false, updatable = false)
    private long orderId;
    @Column(nullable = false, updatable = false, length = 50)
    private String eventType;
    @Column(nullable = false, updatable = false)
    private int schemaVersion;
    @Column(nullable = false, updatable = false, length = 50)
    private String messageKey;
    // MySQL JSON 타입은 저장할 때 키 순서·공백을 다시 쓰므로, 보낼 문자열을 그대로 돌려받으려고 TEXT로 둔다.
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(nullable = false)
    private int attemptCount;
    // 행 내용 때문에 생긴 실패만 센다. broker 수준 실패는 attempt_count만 늘리고 여기엔 세지 않는다(D114).
    @Column(nullable = false)
    private int rowFailureCount;
    @Column(nullable = false)
    private Instant nextAttemptAt;
    @Column(length = 500)
    private String lastError;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    private Instant publishedAt;

    protected OrderConfirmedOutboxJpaEntity() {
    }

    long id() {
        return id;
    }

    String eventId() {
        return eventId;
    }

    long orderId() {
        return orderId;
    }

    String messageKey() {
        return messageKey;
    }

    String payload() {
        return payload;
    }

    int attemptCount() {
        return attemptCount;
    }

    int rowFailureCount() {
        return rowFailureCount;
    }

    // 처음 기록할 때는 바로 relay 대상이 되도록 다음 시도 시각을 기록 시각과 같게 둔다.
    static OrderConfirmedOutboxJpaEntity pending(String eventId, long orderId, String eventType, int schemaVersion,
        String messageKey, String payload, Instant now) {
        OrderConfirmedOutboxJpaEntity entity = new OrderConfirmedOutboxJpaEntity();
        entity.eventId = eventId;
        entity.orderId = orderId;
        entity.eventType = eventType;
        entity.schemaVersion = schemaVersion;
        entity.messageKey = messageKey;
        entity.payload = payload;
        entity.status = "PENDING";
        entity.attemptCount = 0;
        entity.rowFailureCount = 0;
        entity.nextAttemptAt = now;
        entity.createdAt = now;
        return entity;
    }
}
