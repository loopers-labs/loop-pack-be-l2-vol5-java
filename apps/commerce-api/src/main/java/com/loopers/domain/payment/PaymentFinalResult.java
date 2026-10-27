package com.loopers.domain.payment;

/**
 * TX B 에 적용할 검증된 최종 결과. status 는 SUCCESS 또는 FAILED 다.
 * transactionKey 는 거래 생성 전 거절(REJECTED_BEFORE_CREATE)이면 null 이다.
 */
public record PaymentFinalResult(
    PaymentAttempt.Status status,
    PaymentAttempt.FailureReason failureReason,
    String transactionKey
) {

    public PaymentFinalResult {
        if (status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("최종 결과는 SUCCESS 또는 FAILED 여야 한다: " + status);
        }
        if (status == PaymentAttempt.Status.FAILED && failureReason == null) {
            throw new IllegalArgumentException("FAILED 에는 실패 사유가 필요하다.");
        }
    }

    public static PaymentFinalResult success(String transactionKey) {
        return new PaymentFinalResult(PaymentAttempt.Status.SUCCESS, null, transactionKey);
    }

    public static PaymentFinalResult failed(PaymentAttempt.FailureReason failureReason, String transactionKey) {
        return new PaymentFinalResult(PaymentAttempt.Status.FAILED, failureReason, transactionKey);
    }

    public boolean isSuccess() {
        return status == PaymentAttempt.Status.SUCCESS;
    }
}