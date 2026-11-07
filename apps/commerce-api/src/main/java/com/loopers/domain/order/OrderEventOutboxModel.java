package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * AG-06 포함 개념 주문 이벤트 Outbox (TB-14 order_event_outbox). 확정 트랜잭션이 주문 확정과 함께 한 행을 남기고, relay 가 commit 뒤 broker 로 보낸다.
 * 사건(eventId·orderId·eventType·schemaVersion)과 전송 내용(messageKey·payload)은 만든 뒤 바꾸지 않는다. relay 는 저장된 payload 를 그대로 보내고, 주문·상품을 다시 읽어 만들지 않는다.
 * 바뀌는 것은 전송 상태(status·시도·오류·발행 확인)뿐이다. createdAt 은 기록 시각, publishedAt 은 broker 확인을 저장한 시각이다.
 * 전이: PENDING —startAttempt→ PENDING(시도 중) —markPublished→ PUBLISHED / —recordFailure→ PENDING(재시도) | BLOCKED(연속 실패 상한). BLOCKED —resume→ PENDING.
 * event_id 고유: 같은 확정 사건은 한 행이다. 인덱스 IX-15 는 relay 의 대상 조회(status, next_attempt_at, id) 용이다.
 */
@Entity
@Table(name = "order_event_outbox",
    uniqueConstraints = @UniqueConstraint(name = "ux_order_event_outbox_event", columnNames = "event_id"),
    indexes = @Index(name = "ix_order_event_outbox_due", columnList = "status, next_attempt_at, id"))
public class OrderEventOutboxModel extends BaseEntity {

    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;

    /** 원본 주문 (같은 AG 의 루트). ID 만. */
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private Integer schemaVersion;

    /** broker record key. */
    @Column(name = "message_key", nullable = false, updatable = false, length = 64)
    private String messageKey;

    /** broker record value 그대로 (JSON 문자열). JSON 타입이 아닌 TEXT 라 저장한 문자 그대로 다시 읽힌다. */
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    /** 지금까지 전송을 시작한 횟수. 재개해도 줄지 않는다 (관찰용). */
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    /** 마지막 성공·재개 뒤 연속 실패 횟수. 상한에 닿으면 BLOCKED. */
    @Column(name = "failure_count", nullable = false)
    private Integer failureCount;

    /** PENDING 이 이 시각 이후 전송 대상이다. */
    @Column(name = "next_attempt_at", nullable = false)
    private ZonedDateTime nextAttemptAt;

    /** 마지막 실패의 예외 종류와 메시지 (잘라서). payload·카드·비밀은 넣지 않는다. */
    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "published_at")
    private ZonedDateTime publishedAt;

    @Column(name = "published_partition")
    private Integer publishedPartition;

    @Column(name = "published_offset")
    private Long publishedOffset;

    protected OrderEventOutboxModel() {}

    private OrderEventOutboxModel(String eventId, Long orderId, String eventType, int schemaVersion, String messageKey, String payload,
                                  ZonedDateTime availableAt) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.messageKey = messageKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.failureCount = 0;
        this.nextAttemptAt = availableAt;
    }

    /** 새 사건 한 행. PENDING, availableAt 부터 전송 대상이다. */
    public static OrderEventOutboxModel pending(String eventId, Long orderId, String eventType, int schemaVersion, String messageKey,
                                                String payload, ZonedDateTime availableAt) {
        requireText(eventId, "eventId");
        requireText(eventType, "eventType");
        requireText(messageKey, "messageKey");
        requireText(payload, "payload");
        if (orderId == null || availableAt == null) {
            throw new IllegalArgumentException("Outbox 의 orderId·availableAt 이 없습니다. [eventId = " + eventId + "]");
        }
        return new OrderEventOutboxModel(eventId, orderId, eventType, schemaVersion, messageKey, payload, availableAt);
    }

    /**
     * 전송을 시작한다. 시도 횟수를 올리고, 결과를 저장하지 못하고 멈췄을 때 다시 고를 시각(now + lease)으로 미룬다.
     * PENDING 만 보낼 수 있다.
     */
    public void startAttempt(ZonedDateTime now, Duration lease) {
        ensureStatus(OutboxStatus.PENDING, "전송 시작");
        this.attemptCount++;
        this.nextAttemptAt = now.plus(lease);
    }

    /** broker 가 기록을 확인했다. 확인을 저장한 시각과 기록 위치를 남긴다. 이후 다시 보내지 않는다. */
    public void markPublished(ZonedDateTime now, int partition, long offset) {
        ensureStatus(OutboxStatus.PENDING, "발행 확인 저장");
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.publishedPartition = partition;
        this.publishedOffset = offset;
        this.failureCount = 0;
    }

    /**
     * 전송 실패(예외·확인 대기 초과). 연속 실패가 maxFailures 에 닿으면 BLOCKED, 아니면 now + retryDelay 에 다시 고른다.
     * 대기 초과는 broker 가 기록하지 않았다는 뜻이 아니다. 같은 사건을 다시 보낸다.
     */
    public void recordFailure(ZonedDateTime now, String error, Duration retryDelay, int maxFailures) {
        ensureStatus(OutboxStatus.PENDING, "실패 기록");
        this.failureCount++;
        this.lastError = truncate(error);
        if (failureCount >= maxFailures) {
            this.status = OutboxStatus.BLOCKED;
            return;
        }
        this.nextAttemptAt = now.plus(retryDelay);
    }

    /** BLOCKED 를 같은 행 그대로 PENDING 으로 되돌린다. 연속 실패만 0 으로, 누적 시도·마지막 오류는 남긴다. 바로 전송 대상이다. */
    public void resume(ZonedDateTime now) {
        ensureStatus(OutboxStatus.BLOCKED, "재개");
        this.status = OutboxStatus.PENDING;
        this.failureCount = 0;
        this.nextAttemptAt = now;
    }

    public boolean isBlocked() {
        return status == OutboxStatus.BLOCKED;
    }

    private void ensureStatus(OutboxStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException("Outbox " + action + " 불가 [eventId = " + eventId + ", status = " + status + "]");
        }
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= 500) {
            return error;
        }
        return error.substring(0, 500);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Outbox 의 " + name + " 이(가) 비어 있습니다.");
        }
    }

    public String getEventId() {
        return eventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getEventType() {
        return eventType;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public Integer getFailureCount() {
        return failureCount;
    }

    public ZonedDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
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
}
