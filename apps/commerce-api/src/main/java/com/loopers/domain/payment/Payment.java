package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;
import java.util.UUID;

public record Payment(
        long orderId,
        UUID paymentAttemptKey,
        String providerOrderId,
        long amount,
        PaymentStatus status,
        String transactionKey,
        ZonedDateTime pgRequestStartedAt) {

    public Payment {
        if (orderId <= 0 || paymentAttemptKey == null || paymentAttemptKey.version() != 7
                || paymentAttemptKey.variant() != 2 || providerOrderId == null || providerOrderId.length() < 6
                || amount <= 0 || status == null || pgRequestStartedAt == null
                || (status == PaymentStatus.SUCCESS && (transactionKey == null || transactionKey.isBlank()))) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
    }

    public static Payment start(long orderId, UUID paymentAttemptKey, long amount) {
        return new Payment(orderId, paymentAttemptKey, "LP-ORD-%010d".formatted(orderId), amount,
                PaymentStatus.PENDING, null, ZonedDateTime.now());
    }

    public Payment accepted(String receivedTransactionKey) {
        if (receivedTransactionKey == null || receivedTransactionKey.isBlank()
                || (transactionKey != null && !transactionKey.equals(receivedTransactionKey))
                || (status == PaymentStatus.FAILED && transactionKey == null)) {
            throw new CoreException(ErrorType.CONFLICT);
        }
        if (status.isTerminal()) {
            return this;
        }
        return new Payment(orderId, paymentAttemptKey, providerOrderId, amount,
                PaymentStatus.PENDING, receivedTransactionKey, pgRequestStartedAt);
    }

    public Payment unknown() {
        if (status.isTerminal()) {
            return this;
        }
        return new Payment(orderId, paymentAttemptKey, providerOrderId, amount,
                PaymentStatus.UNKNOWN, transactionKey, pgRequestStartedAt);
    }

    public Payment succeeded(String receivedTransactionKey) {
        if (status == PaymentStatus.FAILED) {
            throw new CoreException(ErrorType.CONFLICT);
        }
        Payment acceptedPayment = accepted(receivedTransactionKey);
        return new Payment(orderId, paymentAttemptKey, providerOrderId, amount,
                PaymentStatus.SUCCESS, acceptedPayment.transactionKey(), pgRequestStartedAt);
    }

    public Payment failed(String receivedTransactionKey) {
        if (status == PaymentStatus.SUCCESS) {
            throw new CoreException(ErrorType.CONFLICT);
        }
        Payment acceptedPayment = accepted(receivedTransactionKey);
        return new Payment(orderId, paymentAttemptKey, providerOrderId, amount,
                PaymentStatus.FAILED, acceptedPayment.transactionKey(), pgRequestStartedAt);
    }

    public Payment rejectedBeforeCreate() {
        if (transactionKey != null || status == PaymentStatus.SUCCESS) {
            throw new CoreException(ErrorType.CONFLICT);
        }
        if (status == PaymentStatus.FAILED) {
            return this;
        }
        return new Payment(orderId, paymentAttemptKey, providerOrderId, amount,
                PaymentStatus.FAILED, null, pgRequestStartedAt);
    }
}
