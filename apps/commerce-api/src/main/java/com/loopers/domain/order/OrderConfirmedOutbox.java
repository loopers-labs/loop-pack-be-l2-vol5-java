package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 주문 확정 사건의 Transactional Outbox 행 (7주차 설계 D11~D15).
 * 주문 CONFIRMED·예약 소비와 같은 DB 트랜잭션에서 PENDING 으로 insert 되고, relay 가 broker 확인 뒤 PUBLISHED 로 바꾼다.
 * - eventId·messageKey·payload 는 W6 계약 그대로이며 생성 뒤 바꾸지 않는다. payload 는 확정 TX 안에서 한 번 직렬화한 JSON 문자열이다
 * - event_id·order_id 각각 UNIQUE. 유일성은 주문 확정 UPDATE 의 가드가 먼저 보장하고 제약은 최후 방어다
 * - attemptCount·nextAttemptAt·lastError 는 relay 관찰용 컬럼. 초기값은 0 · 저장 시각 · null 이며 relay 가 시도 시작·결과 TX 에서 갱신한다 (D23)
 * - 상태 전이는 전부 리포지터리의 조건부 UPDATE(WHERE status = …)가 맡는다. 늦은 결과가 이미 끝난 행을 덮어쓰지 못한다 (D25)
 */
@Getter
@Entity
@Table(
    name = "order_confirmed_outbox",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_confirmed_outbox_event_id", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_order_confirmed_outbox_order_id", columnNames = "order_id")
    },
    // relay 의 PENDING 묶음 조회 (WHERE status = PENDING AND next_attempt_at <= now ORDER BY next_attempt_at, id) 와 같은 순서 (D32)
    indexes = @Index(name = "idx_order_confirmed_outbox_status_next_attempt", columnList = "status, next_attempt_at, id")
)
public class OrderConfirmedOutbox extends BaseEntity {

    /**
     * PENDING: 아직 broker 확인 전(재시도 대상). PUBLISHED: relay 가 broker 확인 뒤 저장 (소비 완료가 아니다).
     * BLOCKED: 다시 보내도 같은 결과인 영구 오류. 완료가 아니라 사람이 판단해 재개할 미해결 행 (D14·D29).
     * 문자열 컬럼이라 값이 늘어도 스키마 변경이 없다.
     */
    public enum Status {
        PENDING, PUBLISHED, BLOCKED
    }

    @Column(nullable = false)
    private String eventId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private int schemaVersion;

    @Column(nullable = false)
    private String messageKey;

    /** JSON 문자열. MySQL JSON 타입은 정규화로 내용이 바뀔 수 있어 TEXT 로 둔다 (D12). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** broker 확인 뒤 PUBLISHED 로 바꾼 시각. 기록 시각(createdAt)과 구분한다 (D15). */
    private ZonedDateTime publishedAt;

    @Column(nullable = false)
    private int attemptCount;

    /** relay 가 "PENDING 이고 nextAttemptAt <= now" 한 조건으로 대상을 찾도록 처음부터 저장 시각을 넣는다 (D15). */
    @Column(nullable = false)
    private ZonedDateTime nextAttemptAt;

    @Column(length = 1000)
    private String lastError;

    protected OrderConfirmedOutbox() {}

    private OrderConfirmedOutbox(OrderConfirmedEvent event, String payload, ZonedDateTime at) {
        this.eventId = event.eventId();
        this.orderId = event.orderId();
        this.eventType = event.eventType();
        this.schemaVersion = event.schemaVersion();
        this.messageKey = event.key();
        this.payload = payload;
        this.status = Status.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = at;
    }

    /**
     * @param event   확정 TX 안에서 만든 발행 payload 객체 (eventId·key 의 출처)
     * @param payload 같은 TX 안에서 event 를 직렬화한 JSON 문자열. relay 는 이 문자열을 그대로 보낸다
     * @param at      확정 시각(Clock). nextAttemptAt 의 초기값
     */
    public static OrderConfirmedOutbox pending(OrderConfirmedEvent event, String payload, ZonedDateTime at) {
        return new OrderConfirmedOutbox(event, payload, at);
    }
}
