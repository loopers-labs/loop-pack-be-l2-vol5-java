package com.loopers.domain.outbox;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

/**
 * 확정 TX에서 "보낼 사실"을 함께 저장한 한 줄(docs/week7/decisions.md D-1, INV-701). relay가 이 줄을 읽어 broker가 받을
 * 때까지 다시 보낸다.
 *
 * - payload는 확정 TX에서 한 번 만든 계약 JSON 문자열이다. 만든 뒤 바꾸는 메서드가 없다 — relay는 이 문자열과
 *   message_key를 그대로 보낸다(INV-702). 컬럼은 MySQL JSON이 아니라 TEXT다. JSON 타입은 key 순서·공백을 정규화해
 *   꺼낸 글자가 넣은 글자와 달라진다(D-1).
 * - PUBLISHED는 broker 확인 뒤에만, PENDING에서만 간다(INV-703). 재개는 BLOCKED인 같은 행을 PENDING으로 되돌린다 —
 *   eventId·payload는 그대로다(INV-705).
 * - 시각은 모두 호출자가 넘긴다. relay는 주입한 Clock으로 "지금"을 정한다(D-11). 엔티티는 시계를 갖지 않는다.
 * - created_at은 BaseEntity의 것(저장 순간의 JVM 시각)을 쓴다. 팩토리가 받는 시각은 next_attempt_at의 첫 값에만 쓴다(D-16).
 *   BaseEntity의 delete()가 열려 있지만, 지우는 경로를 만들지 않는 것으로 INV-705를 지킨다.
 */
// uk(event_id): 같은 이벤트가 두 줄 생기는 것을 막는 마지막 방어선이다(INV-701, D-5 동시 복구).
// idx(order_id): 주문으로 행을 찾는다(복구·확인).
// idx(status, next_attempt_at, id): relay가 "보낼 차례가 된 PENDING을 id 순서로" 고르는 경로다(D-15).
@Entity
@Table(
    name = "outbox_event",
    uniqueConstraints = @UniqueConstraint(name = "uk_outbox_event_event_id", columnNames = "event_id"),
    indexes = {
        @Index(name = "idx_outbox_event_order_id", columnList = "order_id"),
        @Index(name = "idx_outbox_event_status_next_attempt_at_id", columnList = "status, next_attempt_at, id")
    }
)
public class OutboxEvent extends BaseEntity {

    /** last_error에 남기는 최대 글자 수. 원인 예외 메시지가 길어도 행이 커지지 않게 자른다. */
    public static final int MAX_ERROR_LENGTH = 1000;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "message_key", nullable = false, updatable = false)
    private String messageKey;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    @Column(name = "published_at")
    private ZonedDateTime publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private ZonedDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    protected OutboxEvent() {}

    private OutboxEvent(String eventId, Long orderId, String eventType, int schemaVersion, String messageKey,
                        String payload, ZonedDateTime firstAttemptAt) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.messageKey = messageKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = firstAttemptAt;
    }

    /**
     * 보낼 차례를 기다리는 PENDING 행을 만든다. 시도 횟수 0, 오류 없음이다.
     *
     * @param firstAttemptAt relay가 처음 집을 수 있는 시각(next_attempt_at의 첫 값). created_at은 BaseEntity가 채운다(D-16).
     */
    public static OutboxEvent pending(String eventId, Long orderId, String eventType, int schemaVersion, String messageKey,
                                      String payload, ZonedDateTime firstAttemptAt) {
        requireText(eventId, "eventId");
        if (orderId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "Outbox 행의 orderId는 비어있을 수 없습니다.");
        }
        requireText(eventType, "eventType");
        if (schemaVersion <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "Outbox 행의 schemaVersion은 양수여야 합니다.");
        }
        requireText(messageKey, "messageKey");
        requireText(payload, "payload");
        if (firstAttemptAt == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "Outbox 행의 첫 시도 시각은 비어있을 수 없습니다.");
        }
        return new OutboxEvent(eventId, orderId, eventType, schemaVersion, messageKey, payload, firstAttemptAt);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "Outbox 행의 " + name + "은(는) 비어있을 수 없습니다.");
        }
    }

    /** broker가 받았다고 응답한 뒤에만 부른다(INV-703). PENDING에서만 간다 — 이미 PUBLISHED·BLOCKED면 거절한다. */
    public void markPublished(ZonedDateTime publishedAt) {
        assertPending("발행 완료로 바꿀");
        requireTime(publishedAt, "발행 시각");
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
    }

    /** 보내기가 실패했다. PENDING을 유지하고 다음에 다시 집을 시각을 적는다(D-2, D-3 백오프). */
    public void recordFailure(String error, ZonedDateTime nextAttemptAt) {
        assertPending("실패를 기록할");
        requireTime(nextAttemptAt, "다음 시도 시각");
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt;
    }

    /** 24시간이 지나도 실패해 relay가 더 집지 않게 뺀다(D-3). 행은 지우지 않고 마지막 오류도 남긴다. */
    public void block(String error) {
        assertPending("BLOCKED로 바꿀");
        this.status = OutboxStatus.BLOCKED;
        this.lastError = truncate(error);
    }

    /** BLOCKED인 같은 행을 PENDING으로 되돌린다. eventId·payload는 그대로다 — 새 eventId면 consumer가 다른 이벤트로 본다(INV-705). */
    public void resume(ZonedDateTime nextAttemptAt) {
        if (status != OutboxStatus.BLOCKED) {
            throw new CoreException(ErrorType.CONFLICT, "BLOCKED가 아닌 Outbox 행은 재개할 수 없습니다. (현재: " + status + ")");
        }
        requireTime(nextAttemptAt, "다음 시도 시각");
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
    }

    private void assertPending(String action) {
        if (status != OutboxStatus.PENDING) {
            throw new CoreException(ErrorType.CONFLICT, "PENDING이 아닌 Outbox 행은 " + action + " 수 없습니다. (현재: " + status + ")");
        }
    }

    private static void requireTime(ZonedDateTime time, String name) {
        if (time == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, name + "은(는) 비어있을 수 없습니다.");
        }
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
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

    public int getSchemaVersion() {
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

    public ZonedDateTime getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public ZonedDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
