package com.loopers.outbox.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;

import java.time.ZonedDateTime;

/**
 * 범용 발행 기록 (W6 ADR-W6-03). 업무 변경과 같은 TX에서 published_at = null로 만들고, send 콜백이 성공을 알리면 표시한다.
 * "보내야 할 것" + "보냄 표시"라서 W7 relay가 published_at IS NULL인 행을 다시 보낼 수 있다. 이번 주에는 relay가 없다.
 * <p>
 * 결과 표시는 send 콜백 스레드에서 오므로 엔티티를 읽어 save하지 않고 조건부 UPDATE(EventOutboxPort)로 한다 —
 * 다른 주체가 같은 행을 덮어쓰지 않게 (plan.md "한 행을 쓰는 주체가 둘이면").
 */
@Entity
@Table(
    name = "event_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_event_outbox_event_id", columnNames = "event_id"),
    indexes = {
        @Index(name = "idx_event_outbox_unpublished", columnList = "published_at, created_at"),
        @Index(name = "idx_event_outbox_aggregate", columnList = "aggregate_type, aggregate_id")
    }
)
public class EventOutbox extends BaseEntity {

    @Column(name = "event_id", nullable = false, length = 36)
    @Comment("이벤트 신원(UUID). 재발행해도 같은 값 (ADR-W6-01)")
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    @Comment("이벤트를 낸 대상 종류 — ORDER")
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    @Comment("대상 ID — 주문 ID")
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    @Comment("이벤트 종류 — OrderConfirmed")
    private String eventType;

    @Column(name = "topic", nullable = false, length = 200)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 200)
    @Comment("partition을 고르는 key — 주문 ID 문자열")
    private String messageKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    @Comment("보낸 JSON 원문. MySQL JSON 타입은 키 순서·공백을 다시 만들어 원문이 보존되지 않아 TEXT")
    private String payload;

    @Column(name = "published_at")
    @Comment("send 콜백이 성공을 알린 시각. null이면 미발행 — 누락 판정 기준")
    private ZonedDateTime publishedAt;

    @Column(name = "published_partition")
    private Integer publishedPartition;

    @Column(name = "published_offset")
    private Long publishedOffset;

    @Column(name = "last_error", length = 1000)
    @Comment("마지막 send 실패 원인 — 진단용. 판정은 published_at으로만")
    private String lastError;

    protected EventOutbox() {}

    private EventOutbox(OutboxMessage message, String payload) {
        this.eventId = message.eventId();
        this.aggregateType = message.aggregateType();
        this.aggregateId = message.aggregateId();
        this.eventType = message.eventType();
        this.topic = message.topic();
        this.messageKey = message.key();
        this.payload = payload;
    }

    public static EventOutbox of(OutboxMessage message, String payload) {
        return new EventOutbox(message, payload);
    }

    public String getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public ZonedDateTime getPublishedAt() {
        return publishedAt;
    }

    public Integer getPublishedPartition() {
        return publishedPartition;
    }

    public Long getPublishedOffset() {
        return publishedOffset;
    }

    public String getLastError() {
        return lastError;
    }
}
