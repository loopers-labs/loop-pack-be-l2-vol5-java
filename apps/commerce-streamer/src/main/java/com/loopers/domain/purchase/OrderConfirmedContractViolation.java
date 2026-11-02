package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * 계약을 어긴 기록(읽을 수 없거나, 읽혀도 약속을 어김)의 원본(docs/week6/decisions.md D-8, D-14, D-19, D-22).
 *
 * - 확실한 건 주소와 원본 바이트뿐이라 이 둘만 NOT NULL이다. key는 읽혔을 때만 남긴다.
 * - 원본은 바이트 그대로다. 문자열로 바꾸면 잘못된 UTF-8이 대체 문자로 바뀌어 되돌릴 수 없다(D-14).
 * - 주소에 unique를 건다. 재전달돼도 한 번만 남는다(D-22).
 * 고치는 길은 그 시각의 producer 배포에서 버그를 찾아 고치고 주문 DB에서 재발행하는 것이다(D-4).
 */
@Entity
@Table(
    name = "order_confirmed_contract_violation",
    uniqueConstraints = @UniqueConstraint(name = "uk_order_confirmed_contract_violation_address",
        columnNames = {"record_topic", "record_partition", "record_offset"})
)
public class OrderConfirmedContractViolation extends BaseEntity {

    /** MySQL mediumblob(16MiB) — broker의 기본 최대 메시지 크기(1MiB)보다 넉넉하다. */
    private static final int RAW_VALUE_MAX = 16_777_215;

    @Column(name = "record_topic", nullable = false)
    private String recordTopic;

    @Column(name = "record_partition", nullable = false)
    private int recordPartition;

    @Column(name = "record_offset", nullable = false)
    private long recordOffset;

    @Column(name = "record_key")
    private String recordKey;

    @Column(name = "raw_value", nullable = false, length = RAW_VALUE_MAX)
    private byte[] rawValue;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "recorded_at", nullable = false)
    private ZonedDateTime recordedAt;

    protected OrderConfirmedContractViolation() {}

    public OrderConfirmedContractViolation(RecordAddress address, String recordKey, byte[] rawValue, String reason,
                                           ZonedDateTime recordedAt) {
        this.recordTopic = Objects.requireNonNull(address.topic());
        this.recordPartition = address.partition();
        this.recordOffset = address.offset();
        this.recordKey = recordKey;
        this.rawValue = Objects.requireNonNull(rawValue).clone();
        this.reason = truncate(Objects.requireNonNull(reason));
        this.recordedAt = recordedAt;
    }

    private static String truncate(String reason) {
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000);
    }

    public RecordAddress getAddress() {
        return new RecordAddress(recordTopic, recordPartition, recordOffset);
    }

    public String getRecordKey() {
        return recordKey;
    }

    public byte[] getRawValue() {
        return rawValue.clone();
    }

    public String getReason() {
        return reason;
    }

    public ZonedDateTime getRecordedAt() {
        return recordedAt;
    }
}
