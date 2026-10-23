package com.loopers.application.payment.pg;

/** 결제 요청의 결과. {@code transaction} 은 {@code ACCEPTED} 일 때만 있다. */
public record PgPaymentRequestResult(Outcome outcome, PgTransactionInfo transaction) {

    public enum Outcome {
        /** 거래를 만들었다(202). 요청과 식별·금액이 일치하고 PENDING 일 때만 이 결과다. */
        ACCEPTED,
        /** 거래를 저장하기 전에 확정 거절했다. 이 요청의 거래는 지금도 앞으로도 없다. */
        REJECTED_WITHOUT_TRANSACTION,
        /** 그 밖의 모든 결과(timeout, 연결 끊김, 5xx, 해석할 수 없는 응답). 거래가 생겼는지 모른다. */
        UNKNOWN
    }

    public static PgPaymentRequestResult accepted(PgTransactionInfo transaction) {
        return new PgPaymentRequestResult(Outcome.ACCEPTED, transaction);
    }

    public static PgPaymentRequestResult rejectedWithoutTransaction() {
        return new PgPaymentRequestResult(Outcome.REJECTED_WITHOUT_TRANSACTION, null);
    }

    public static PgPaymentRequestResult unknown() {
        return new PgPaymentRequestResult(Outcome.UNKNOWN, null);
    }
}
