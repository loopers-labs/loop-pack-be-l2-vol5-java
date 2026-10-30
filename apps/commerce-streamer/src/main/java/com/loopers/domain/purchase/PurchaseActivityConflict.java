package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * 같은 확정을 두고 이미 처리한 사실과 새로 받은 사실이 어긋났다(docs/week6/decisions.md D-7, D-18). 다시 해도 안 풀리므로
 * 기록하고 ack한다(D-8). 운영자가 무엇이 다른지 바로 보도록 처음 사실과 새 사실을 둘 다 남긴다.
 *
 * - 파싱에 성공한 뒤에만 생기므로 주문 번호와 두 사실은 항상 있다 — NOT NULL로 DB가 막는다(D-22).
 * - 주소(topic, partition, offset)에 unique를 건다. 기록 뒤 ack 전에 죽어 재전달돼도 한 번만 남는다(D-22).
 * - firstEventId는 처리 기록에서 찾았을 때만 있다(처리 기록 없이 활동만 있는 불일치면 비어 있다).
 */
@Entity
@Table(
    name = "purchase_activity_conflict",
    uniqueConstraints = @UniqueConstraint(name = "uk_purchase_activity_conflict_address",
        columnNames = {"record_topic", "record_partition", "record_offset"}),
    indexes = @Index(name = "idx_purchase_activity_conflict_order_id", columnList = "order_id")
)
public class PurchaseActivityConflict extends BaseEntity {

    /** 어긋난 모양(D-18 표). */
    public enum Reason {
        /** 같은 eventId인데 orderId나 사실이 다르다. */
        SAME_EVENT_DIFFERENT_FACT,
        /** 같은 orderId인데 eventId가 다르다 — 내용이 같아도 식별 계약 위반이다. */
        SAME_ORDER_DIFFERENT_EVENT,
        /** 처리 기록 없이 구매 활동만 있다(활동 unique만 걸림). */
        ACTIVITY_WITHOUT_PROCESSED_RECORD
    }

    @Column(name = "record_topic", nullable = false)
    private String recordTopic;

    @Column(name = "record_partition", nullable = false)
    private int recordPartition;

    @Column(name = "record_offset", nullable = false)
    private long recordOffset;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "first_event_id")
    private String firstEventId;

    @Column(name = "first_fact", nullable = false, columnDefinition = "text")
    private String firstFact;

    @Column(name = "new_fact", nullable = false, columnDefinition = "text")
    private String newFact;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    private Reason reason;

    @Column(name = "recorded_at", nullable = false)
    private ZonedDateTime recordedAt;

    protected PurchaseActivityConflict() {}

    public PurchaseActivityConflict(RecordAddress address, OrderConfirmation incoming, PurchaseFact newFact,
                                    DuplicateVerdict.Conflict conflict, ZonedDateTime recordedAt) {
        this.recordTopic = Objects.requireNonNull(address.topic());
        this.recordPartition = address.partition();
        this.recordOffset = address.offset();
        this.orderId = Objects.requireNonNull(incoming.orderId());
        this.eventId = Objects.requireNonNull(incoming.eventId());
        this.firstEventId = conflict.firstEventId();
        this.firstFact = Objects.requireNonNull(conflict.firstFact()).value();
        this.newFact = Objects.requireNonNull(newFact).value();
        this.reason = Objects.requireNonNull(conflict.reason());
        this.recordedAt = recordedAt;
    }

    public RecordAddress getAddress() {
        return new RecordAddress(recordTopic, recordPartition, recordOffset);
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getFirstEventId() {
        return firstEventId;
    }

    public String getFirstFact() {
        return firstFact;
    }

    public String getNewFact() {
        return newFact;
    }

    public Reason getReason() {
        return reason;
    }

    public ZonedDateTime getRecordedAt() {
        return recordedAt;
    }
}
