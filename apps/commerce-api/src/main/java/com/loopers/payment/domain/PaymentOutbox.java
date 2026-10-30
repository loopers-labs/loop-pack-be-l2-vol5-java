package com.loopers.payment.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;

import java.time.ZonedDateTime;

/**
 * 결제 생성 요청을 "보내야 한다"는 장부 한 줄 (ADR-W5-01). TX A에서 결제와 함께 저장한다.
 * 상태 전이는 동시 실행끼리 경쟁하므로 엔티티 메서드가 아니라 조건부 UPDATE(PaymentOutboxPort)로 한다.
 */
@Entity
@Table(
    name = "payment_outbox",
    uniqueConstraints = @UniqueConstraint(name = "uk_payment_outbox_payment", columnNames = "payment_id"),
    indexes = @Index(name = "idx_payment_outbox_status", columnList = "status, created_at")
)
public class PaymentOutbox extends BaseEntity {

    public static final String PAYMENT_REQUESTED = "PAYMENT_REQUESTED";

    @Column(name = "payment_id", nullable = false)
    @Comment("결제 시도 식별자. unique → 시도당 발송 장부 1줄")
    private Long paymentId;

    @Column(name = "event_type", nullable = false)
    @Comment("장부 종류 — PAYMENT_REQUESTED")
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Comment("PENDING·CLAIMED·DONE·CLOSED (ADR-W5-01·03)")
    private PaymentOutboxStatus status;

    @Column(name = "claimed_at")
    @Comment("발송 권한을 얻은 시각")
    private ZonedDateTime claimedAt;

    @Column(name = "completed_at")
    @Comment("DONE·CLOSED가 된 시각")
    private ZonedDateTime completedAt;

    protected PaymentOutbox() {}

    private PaymentOutbox(Long paymentId) {
        this.paymentId = paymentId;
        this.eventType = PAYMENT_REQUESTED;
        this.status = PaymentOutboxStatus.PENDING;
    }

    public static PaymentOutbox paymentRequested(Long paymentId) {
        return new PaymentOutbox(paymentId);
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getEventType() {
        return eventType;
    }

    public PaymentOutboxStatus getStatus() {
        return status;
    }

    public ZonedDateTime getClaimedAt() {
        return claimedAt;
    }

    public ZonedDateTime getCompletedAt() {
        return completedAt;
    }
}
