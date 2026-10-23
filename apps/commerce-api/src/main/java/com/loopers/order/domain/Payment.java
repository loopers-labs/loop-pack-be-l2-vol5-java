package com.loopers.order.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.order.domain.PaymentGateway.Approved;
import com.loopers.order.domain.PaymentGateway.DispatchRejected;
import com.loopers.order.domain.PaymentGateway.ApprovalResult;
import com.loopers.order.domain.PaymentGateway.CancellationConfirmed;
import com.loopers.order.domain.PaymentGateway.CancellationRejected;
import com.loopers.order.domain.PaymentGateway.CancellationResult;
import com.loopers.order.domain.PaymentGateway.CancellationUnknown;
import com.loopers.order.domain.PaymentGateway.ProviderError;
import com.loopers.order.domain.PaymentGateway.Unknown;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZonedDateTime;

@Entity
@Table(name = "payments")
@Getter
public class Payment extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 200)
    private String paymentKey;

    @Column(nullable = false, unique = true, length = 100)
    private String paymentAttemptKey;

    @Column(nullable = false, length = 100)
    private String providerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private ZonedDateTime dispatchStartedAt;

    private ZonedDateTime paidAt;

    @Column(length = 200)
    private String transactionKey;

    @Column(columnDefinition = "TEXT")
    private String rawProviderResponse;

    protected Payment() {
    }

    public Payment(PaymentMethod method, Long amount, String paymentAttemptKey, String providerOrderId) {
        this(method, amount, paymentAttemptKey, paymentAttemptKey, providerOrderId);
    }

    public Payment(
        PaymentMethod method,
        Long amount,
        String paymentKey,
        String paymentAttemptKey,
        String providerOrderId
    ) {
        this.method = method;
        this.amount = amount;
        this.paymentKey = paymentKey;
        this.paymentAttemptKey = paymentAttemptKey;
        this.providerOrderId = providerOrderId;
        this.status = PaymentStatus.PENDING;
    }

    public void markDispatchStarted(ZonedDateTime dispatchStartedAt) {
        if (status == PaymentStatus.SUCCESS || status == PaymentStatus.FAILED) {
            throw new CoreException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        this.dispatchStartedAt = dispatchStartedAt;
        if (status == null) {
            this.status = PaymentStatus.PENDING;
        }
    }

    public void recordResult(ApprovalResult result) {
        if (result instanceof Approved approved) {
            this.status = PaymentStatus.SUCCESS;
            this.transactionKey = approved.paymentKey();
            this.paidAt = approved.paidAt().toZonedDateTime();
            this.rawProviderResponse = approved.rawResponse();
        } else if (result instanceof ProviderError providerError) {
            this.status = PaymentStatus.FAILED;
            this.rawProviderResponse = providerError.rawResponse();
        } else if (result instanceof DispatchRejected) {
            this.status = PaymentStatus.FAILED;
        } else if (result instanceof Unknown unknown) {
            this.status = PaymentStatus.UNKNOWN;
            this.rawProviderResponse = unknown.rawResponse();
        }
    }

    public void recordCancellationResult(CancellationResult result) {
        if (result instanceof CancellationConfirmed confirmed) {
            this.status = PaymentStatus.CANCELED;
            recordRawResponse(confirmed.rawResponse());
        } else if (result instanceof CancellationUnknown unknown) {
            this.status = PaymentStatus.CANCEL_PROCESSING;
            recordRawResponse(unknown.rawResponse());
        } else if (result instanceof CancellationRejected rejected) {
            this.status = PaymentStatus.UNKNOWN;
            recordRawResponse(rejected.rawResponse());
        }
    }

    private void recordRawResponse(String rawResponse) {
        if (rawResponse != null) {
            this.rawProviderResponse = rawResponse;
        }
    }

    public PaymentStatus status() {
        return status;
    }

    public String transactionKey() {
        return transactionKey;
    }

    public String paymentKey() {
        return paymentKey;
    }

    public String rawProviderResponse() {
        return rawProviderResponse;
    }

    public String paymentAttemptKey() {
        return paymentAttemptKey;
    }
}
