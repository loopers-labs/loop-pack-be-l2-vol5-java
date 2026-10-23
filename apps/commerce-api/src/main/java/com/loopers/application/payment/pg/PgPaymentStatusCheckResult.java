package com.loopers.application.payment.pg;

import java.util.List;

/** PG 에 결제 상태를 물어본 결과. {@code transactions} 는 {@code ANSWERED} 일 때만 의미가 있다. */
public record PgPaymentStatusCheckResult(Outcome outcome, List<PgTransactionInfo> transactions) {

    public enum Outcome {
        /** PG 가 답했다. 거래가 0건이어도 답이다(아직 안 보이는 것일 수 있어 거래가 없다는 증거는 아니다). */
        ANSWERED,
        /** 알고 있는 거래 키를 PG 가 모른다고 답했다. */
        KEY_NOT_FOUND,
        /** 묻지 못했다(timeout, 5xx, 회로 차단 등). 아무것도 알게 되지 않았다. */
        COULD_NOT_CHECK
    }

    public static PgPaymentStatusCheckResult answered(List<PgTransactionInfo> transactions) {
        return new PgPaymentStatusCheckResult(Outcome.ANSWERED, List.copyOf(transactions));
    }

    public static PgPaymentStatusCheckResult keyNotFound() {
        return new PgPaymentStatusCheckResult(Outcome.KEY_NOT_FOUND, List.of());
    }

    public static PgPaymentStatusCheckResult couldNotCheck() {
        return new PgPaymentStatusCheckResult(Outcome.COULD_NOT_CHECK, List.of());
    }
}
