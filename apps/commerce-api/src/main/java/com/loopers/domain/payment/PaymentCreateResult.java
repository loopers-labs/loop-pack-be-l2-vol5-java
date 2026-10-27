package com.loopers.domain.payment;

/**
 * create 호출 1회의 관찰 결과.
 * ACCEPTED 는 생성 수락(PENDING + transactionKey), REJECTED_NO_EFFECT 는 거래 0건이 확정된 거절,
 * UNKNOWN 은 거래가 생겼는지 알 수 없는 경우(timeout·응답 유실·증거 없는 오류)다.
 * conflictReason 은 응답의 orderId·amount 가 우리가 보낸 값과 다를 때만 채워진다 (설계 1-6 결정 ⑱·㉕). 그때 outcome 은 UNKNOWN 이고 transactionKey 는 없다.
 */
public record PaymentCreateResult(
    Outcome outcome,
    String transactionKey,
    PaymentTransaction.Status status,
    PaymentAttempt.ConflictReason conflictReason
) {

    public enum Outcome {
        ACCEPTED, REJECTED_NO_EFFECT, UNKNOWN
    }

    public static PaymentCreateResult accepted(String transactionKey) {
        return new PaymentCreateResult(Outcome.ACCEPTED, transactionKey, PaymentTransaction.Status.PENDING, null);
    }

    public static PaymentCreateResult rejectedNoEffect() {
        return new PaymentCreateResult(Outcome.REJECTED_NO_EFFECT, null, null, null);
    }

    public static PaymentCreateResult unknown() {
        return new PaymentCreateResult(Outcome.UNKNOWN, null, null, null);
    }

    /**
     * 응답은 왔지만 orderId·amount 가 요청과 달라 접수 증거도 거절 증거도 아닌 경우. 이상 신호로 충돌을 기록한다.
     */
    public static PaymentCreateResult unknownWithConflict(PaymentAttempt.ConflictReason conflictReason) {
        return new PaymentCreateResult(Outcome.UNKNOWN, null, null, conflictReason);
    }
}