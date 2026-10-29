package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

@Entity
@Table(
    name = "payment_attempts",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_payment_attempt_key", columnNames = "payment_attempt_key"),
        @UniqueConstraint(name = "uq_payment_attempt_order", columnNames = "order_id")
    }
)
public class PaymentAttemptModel extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "payment_attempt_key", nullable = false)
    private String paymentAttemptKey;

    @Column(name = "provider_order_id", nullable = false)
    private String providerOrderId;

    @Column(name = "transaction_key")
    private String transactionKey;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentAttemptStatus status;

    @Column(name = "sent_at")
    private ZonedDateTime sentAt;

    protected PaymentAttemptModel() {
    }

    public PaymentAttemptModel(Long orderId, String paymentAttemptKey, String providerOrderId, long amount) {
        if (orderId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 ID는 필수입니다.");
        }
        if (paymentAttemptKey == null || paymentAttemptKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 시도 키는 필수입니다.");
        }
        this.orderId = orderId;
        this.paymentAttemptKey = paymentAttemptKey;
        this.providerOrderId = providerOrderId;
        this.amount = amount;
        this.status = PaymentAttemptStatus.PENDING;
        this.sentAt = ZonedDateTime.now();
    }

    public Long getOrderId() { return orderId; }
    public String getPaymentAttemptKey() { return paymentAttemptKey; }
    public String getProviderOrderId() { return providerOrderId; }
    public String getTransactionKey() { return transactionKey; }
    public long getAmount() { return amount; }
    public PaymentAttemptStatus getStatus() { return status; }
    public ZonedDateTime getSentAt() { return sentAt; }

    public boolean isTerminal() {
        return status == PaymentAttemptStatus.SUCCESS
            || status == PaymentAttemptStatus.FAILED
            || status == PaymentAttemptStatus.REJECTED_NO_EFFECT;
    }

    public void success(String transactionKey) {
        requireNonTerminal();
        this.transactionKey = transactionKey;
        this.status = PaymentAttemptStatus.SUCCESS;
    }

    public void failed() {
        requireNonTerminal();
        this.status = PaymentAttemptStatus.FAILED;
    }

    public void unknown() {
        if (isTerminal()) return;
        this.status = PaymentAttemptStatus.UNKNOWN;
    }

    public void rejectedNoEffect() {
        requireNonTerminal();
        this.status = PaymentAttemptStatus.REJECTED_NO_EFFECT;
    }

    private void requireNonTerminal() {
        if (isTerminal()) {
            throw new CoreException(ErrorType.CONFLICT, "이미 종결된 결제 시도입니다. (상태: " + status + ")");
        }
    }
}
