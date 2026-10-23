package com.loopers.order.domain;

import java.time.OffsetDateTime;

public interface PaymentGateway {

    ApprovalResult approve(ApprovalRequest request);

    ApprovalResult findByPaymentKey(String paymentKey);

    CancellationResult cancel(CancellationRequest request);

    record ApprovalRequest(String paymentKey, String providerOrderId, long amount, String idempotencyKey) {
    }

    record CancellationRequest(String paymentKey, String cancelReason, String idempotencyKey) {
    }

    sealed interface CancellationResult permits CancellationConfirmed, CancellationRejected,
        CancellationUnknown {
    }

    record CancellationConfirmed(String paymentKey, String rawResponse) implements CancellationResult {
    }

    record CancellationRejected(int httpStatus, String code, String message, String rawResponse)
        implements CancellationResult {
    }

    record CancellationUnknown(String rawResponse) implements CancellationResult {
        public CancellationUnknown() {
            this(null);
        }
    }

    sealed interface ApprovalResult permits Approved, ProviderError, DispatchRejected, Unknown {
    }

    record Approved(
        String paymentKey,
        String providerOrderId,
        long amount,
        OffsetDateTime paidAt,
        String rawResponse
    ) implements ApprovalResult {
        public Approved(String paymentKey, String providerOrderId, long amount, OffsetDateTime paidAt) {
            this(paymentKey, providerOrderId, amount, paidAt, null);
        }
    }

    // PG 오류 관찰이며 내부 결제 FAILED를 의미하지 않는다.
    record ProviderError(int httpStatus, String code, String message, String rawResponse) implements ApprovalResult {
        public ProviderError(int httpStatus, String code, String message) {
            this(httpStatus, code, message, null);
        }
    }

    record DispatchRejected(String reason) implements ApprovalResult {
    }

    record Unknown(String rawResponse) implements ApprovalResult {
        public Unknown() {
            this(null);
        }
    }
}
