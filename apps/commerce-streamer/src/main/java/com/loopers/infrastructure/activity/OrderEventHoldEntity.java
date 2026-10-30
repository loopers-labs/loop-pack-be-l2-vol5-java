package com.loopers.infrastructure.activity;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.activity.OrderEventHold;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
    name = "order_event_hold",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_hold_position", columnNames = {"topic", "partition_no", "offset_no"}
    )
)
class OrderEventHoldEntity extends AuditEntity {

    @Column(name = "topic", nullable = false, updatable = false)
    private String topic;

    @Column(name = "partition_no", nullable = false, updatable = false)
    private int partitionNo;

    @Column(name = "offset_no", nullable = false, updatable = false)
    private long offsetNo;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "event_id", updatable = false)
    private String eventId;

    @Column(name = "order_id", updatable = false)
    private Long orderId;

    @Column(name = "record_key", updatable = false)
    private String recordKey;

    @Column(name = "headers", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String headers;

    @Column(name = "record_timestamp", updatable = false)
    private Instant recordTimestamp;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] payload;

    protected OrderEventHoldEntity() {}

    static OrderEventHoldEntity from(OrderEventHold hold, String headersJson) {
        OrderEventHoldEntity entity = new OrderEventHoldEntity();
        entity.topic = hold.record().position().topic();
        entity.partitionNo = hold.record().position().partition();
        entity.offsetNo = hold.record().position().offset();
        entity.reason = hold.reason();
        entity.eventId = hold.eventId();
        entity.orderId = hold.orderId();
        entity.recordKey = hold.record().key();
        entity.headers = headersJson;
        entity.recordTimestamp = hold.record().timestamp();
        entity.payload = hold.record().payload();
        return entity;
    }
}
