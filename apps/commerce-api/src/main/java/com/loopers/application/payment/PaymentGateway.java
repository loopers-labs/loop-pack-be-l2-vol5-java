package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentStatus;

import java.util.List;

public interface PaymentGateway {
    CreatedPayment create(String providerOrderId, long amount, String cardType, String cardNo);

    List<ObservedPayment> findByOrderId(String providerOrderId);

    ObservedPayment findByTransactionKey(String transactionKey);

    record CreatedPayment(String transactionKey, String providerOrderId, long amount) {}

    record ObservedPayment(String transactionKey, String providerOrderId, long amount, PaymentStatus status) {}

    final class RejectedBeforeCreateException extends RuntimeException {
        private final String providerOrderId;
        private final long amount;

        public RejectedBeforeCreateException(String providerOrderId, long amount) {
            super("PG가 거래 생성 전에 결제를 거절했습니다.");
            this.providerOrderId = providerOrderId;
            this.amount = amount;
        }

        public String providerOrderId() {
            return providerOrderId;
        }

        public long amount() {
            return amount;
        }
    }

    final class LookupBlockedException extends RuntimeException {
        public LookupBlockedException(Throwable cause) {
            super("PG 결제 조회가 일시적으로 차단됐습니다.", cause);
        }
    }

    enum FailureReason {
        HTTP_ERROR, INVALID_RESPONSE, TIMEOUT, TRANSPORT, CANCELLED, UNKNOWN
    }

    final class UnconfirmedException extends RuntimeException {
        private final FailureReason reason;
        private final Integer pgHttpStatus;

        public UnconfirmedException() {
            this(null);
        }

        public UnconfirmedException(Throwable cause) {
            this(FailureReason.UNKNOWN, null, cause);
        }

        public UnconfirmedException(FailureReason reason, Integer pgHttpStatus, Throwable cause) {
            super("PG 결제 결과를 확인할 수 없습니다.", cause);
            this.reason = reason;
            this.pgHttpStatus = pgHttpStatus;
        }

        public FailureReason reason() {
            return reason;
        }

        public Integer pgHttpStatus() {
            return pgHttpStatus;
        }
    }
}
