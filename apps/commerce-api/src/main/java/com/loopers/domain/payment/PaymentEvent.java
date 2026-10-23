package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "payment_event", indexes = @Index(name = "idx_payment_event_attempt", columnList = "attempt_id"))
public class PaymentEvent extends BaseEntity {

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;
    @Enumerated(EnumType.STRING)
    @Column(name = "observed_status")
    private PaymentTransaction.Status observedStatus;
    @Column(name = "observed_transaction_key")
    private String observedTransactionKey;
    @Column(name = "observed_amount")
    private Long observedAmount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentAttempt.Result result;
    @Enumerated(EnumType.STRING)
    private Reason reason;

    protected PaymentEvent() {}

    private PaymentEvent(Long attemptId, Source source, PaymentTransaction transaction,
                         PaymentAttempt.Result result, Reason reason) {
        this.attemptId = attemptId;
        this.source = source;
        if (transaction != null) {
            this.observedStatus = transaction.status();
            this.observedTransactionKey = transaction.transactionKey();
            this.observedAmount = transaction.amount();
        }
        this.result = result;
        this.reason = reason;
    }

    public static PaymentEvent of(Long attemptId, Source source, PaymentTransaction transaction,
                                  PaymentAttempt.Result result, Reason reason) {
        return new PaymentEvent(attemptId, source, transaction, result, reason);
    }

    public Long getAttemptId() { return attemptId; }
    public Source getSource() { return source; }
    public PaymentTransaction.Status getObservedStatus() { return observedStatus; }
    public String getObservedTransactionKey() { return observedTransactionKey; }
    public Long getObservedAmount() { return observedAmount; }
    public PaymentAttempt.Result getResult() { return result; }
    public Reason getReason() { return reason; }

    public enum Source { CREATE_RESPONSE, RECONCILE, CALLBACK, BATCH }

    public enum Reason {
        REJECTED_BEFORE_CREATE,
        NOT_SENT,
        // 이전 만료 정책으로 저장된 이력을 읽기 위한 값이다. 새 이력에는 사용하지 않는다.
        NO_TRANSACTION_YET,
        MULTIPLE_TRANSACTIONS,
        ORDER_MISMATCH,
        AMOUNT_MISMATCH,
        OTHER_TRANSACTION_KEY,
        OPPOSITE_FINAL_RESULT
    }
}
