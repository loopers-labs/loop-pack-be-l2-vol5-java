package com.loopers.infrastructure.purchase;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.purchase.OrderEventHold;
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
        entity.topic = hold.received().position().topic();
        entity.partitionNo = hold.received().position().partition();
        entity.offsetNo = hold.received().position().offset();
        entity.reason = hold.reason();
        entity.eventId = hold.eventId();
        entity.orderId = hold.orderId();
        entity.recordKey = hold.received().key();
        entity.headers = headersJson;
        entity.recordTimestamp = hold.received().timestamp();
        entity.payload = hold.received().payload();
        return entity;
    }
}
