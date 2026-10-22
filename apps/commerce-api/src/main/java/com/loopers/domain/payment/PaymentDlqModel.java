package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

@Entity
@Table(name = "payment_dlq")
public class PaymentDlqModel extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "attempt_status")
    private PaymentAttemptStatus attemptStatus;

    @Column(name = "resolved", nullable = false)
    private boolean resolved = false;

    @Column(name = "resolved_at")
    private ZonedDateTime resolvedAt;

    @Column(name = "resolve_note", length = 500)
    private String resolveNote;

    protected PaymentDlqModel() {
    }

    public PaymentDlqModel(Long orderId, String reason, PaymentAttemptStatus attemptStatus) {
        this.orderId = orderId;
        this.reason = reason;
        this.attemptStatus = attemptStatus;
    }

    public void resolve(String note) {
        if (this.resolved) {
            throw new CoreException(ErrorType.BAD_REQUEST, "이미 처리된 DLQ 항목입니다.");
        }
        this.resolved = true;
        this.resolvedAt = ZonedDateTime.now();
        this.resolveNote = note;
    }

    public Long getOrderId() { return orderId; }
    public String getReason() { return reason; }
    public PaymentAttemptStatus getAttemptStatus() { return attemptStatus; }
    public boolean isResolved() { return resolved; }
    public ZonedDateTime getResolvedAt() { return resolvedAt; }
    public String getResolveNote() { return resolveNote; }
}
