package com.loopers.infrastructure.purchase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "order_event_anomaly", uniqueConstraints =
    @UniqueConstraint(name = "uk_order_event_anomaly_record", columnNames = {"topic", "partition_no", "offset_no"}))
public class OrderEventAnomalyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 50)
    private String kind;

    @Column(updatable = false)
    private String eventId;

    @Column(updatable = false)
    private Long orderId;

    @Column(nullable = false, updatable = false, length = 249)
    private String topic;

    @Column(nullable = false, updatable = false)
    private int partitionNo;

    @Column(nullable = false, updatable = false)
    private long offsetNo;

    @Column(updatable = false, columnDefinition = "mediumtext")
    private String rawPayload;

    @Column(nullable = false, updatable = false, length = 1000)
    private String detail;

    @Column(nullable = false, updatable = false)
    private Instant detectedAt;

    protected OrderEventAnomalyJpaEntity() {
    }

    OrderEventAnomalyJpaEntity(
        String kind,
        String eventId,
        Long orderId,
        String topic,
        int partitionNo,
        long offsetNo,
        String rawPayload,
        String detail,
        Instant detectedAt
    ) {
        this.kind = kind;
        this.eventId = eventId;
        this.orderId = orderId;
        this.topic = topic;
        this.partitionNo = partitionNo;
        this.offsetNo = offsetNo;
        this.rawPayload = rawPayload;
        this.detail = detail;
        this.detectedAt = detectedAt;
    }
}
