package com.loopers.domain.pay.model;

import java.util.Objects;

/** 외부 관찰의 종류. 확인 불가는 결제 FAILED와 구분한다. 응답 원문은 보관하지 않는다. */
public record PaymentObservation(Kind kind, String transactionKey, String providerOrderId, long amount,
                                 ProviderPaymentStatus status, Reason reason, Integer httpStatus) {
    public enum Kind { VERIFIED, REJECTED_NO_EFFECT, NOT_FOUND, UNAVAILABLE, CONFLICT }
    public enum Reason { NONE, NOT_FOUND, TIMEOUT, TRANSPORT_ERROR, HTTP_ERROR, INVALID_RESPONSE,
        IDENTITY_MISMATCH, MULTIPLE_TRANSACTIONS, CIRCUIT_BLOCKED }

    public PaymentObservation {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(reason);
        if (httpStatus != null && (kind != Kind.UNAVAILABLE || reason != Reason.HTTP_ERROR
                || httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("HTTP 오류 관찰에는 유효한 상태 코드만 보관합니다.");
        }
        if (kind == Kind.VERIFIED || kind == Kind.REJECTED_NO_EFFECT) {
            if (providerOrderId == null || providerOrderId.isBlank() || amount < 0 || status == null || reason != Reason.NONE) {
                throw new IllegalArgumentException("검증한 결제 식별·결과가 필요합니다.");
            }
            if (kind == Kind.VERIFIED && (transactionKey == null || transactionKey.isBlank() || transactionKey.length() > 64)) {
                throw new IllegalArgumentException("검증한 거래 키가 필요합니다.");
            }
            if (kind == Kind.REJECTED_NO_EFFECT && (transactionKey != null || status != ProviderPaymentStatus.FAILED)) {
                throw new IllegalArgumentException("생성 전 거절에 거래 키를 부여할 수 없습니다.");
            }
        } else if (status != null || transactionKey != null || providerOrderId != null || amount != 0 || reason == Reason.NONE) {
            throw new IllegalArgumentException("확인 불가 관찰에 결제 결과를 부여할 수 없습니다.");
        }
    }

    public static PaymentObservation verified(String key, String orderId, long amount, ProviderPaymentStatus status) {
        return new PaymentObservation(Kind.VERIFIED, key, orderId, amount, status, Reason.NONE, null);
    }

    public static PaymentObservation rejected(String orderId, long amount) {
        return new PaymentObservation(Kind.REJECTED_NO_EFFECT, null, orderId, amount,
                ProviderPaymentStatus.FAILED, Reason.NONE, null);
    }

    public static PaymentObservation unavailable(Reason reason) {
        return new PaymentObservation(Kind.UNAVAILABLE, null, null, 0, null, reason, null);
    }

    public static PaymentObservation httpFailure(int status) {
        return new PaymentObservation(Kind.UNAVAILABLE, null, null, 0, null, Reason.HTTP_ERROR, status);
    }

    public static PaymentObservation missing() {
        return new PaymentObservation(Kind.NOT_FOUND, null, null, 0, null, Reason.NOT_FOUND, null);
    }

    public static PaymentObservation conflict(Reason reason) {
        return new PaymentObservation(Kind.CONFLICT, null, null, 0, null, reason, null);
    }
}
