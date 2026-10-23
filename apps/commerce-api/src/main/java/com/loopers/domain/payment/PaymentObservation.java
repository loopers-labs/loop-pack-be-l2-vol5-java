package com.loopers.domain.payment;

import java.util.List;

/** PG 응답을 읽은 결과. UNKNOWN이면 결과를 확인하지 못한 이유를 함께 담는다. */
public record PaymentObservation(Kind kind, List<PaymentTransaction> transactions, UnknownCause unknownCause) {

    public PaymentObservation {
        transactions = List.copyOf(transactions);
        if (kind == null || (kind != Kind.OBSERVED && !transactions.isEmpty())
            || (kind == Kind.UNKNOWN) != (unknownCause != null)) {
            throw new IllegalArgumentException("PG 관찰 결과가 올바르지 않습니다.");
        }
    }

    public static PaymentObservation observed(List<PaymentTransaction> transactions) {
        return new PaymentObservation(Kind.OBSERVED, transactions, null);
    }

    public static PaymentObservation unknown(UnknownCause cause) {
        return new PaymentObservation(Kind.UNKNOWN, List.of(), cause);
    }

    /** PG가 거래를 만들기 전에 거절했고, 이후에도 만들지 않는다고 증명한 생성 응답. 거래가 없다. */
    public static PaymentObservation rejected() {
        return new PaymentObservation(Kind.REJECTED, List.of(), null);
    }

    /** 생성 요청이 연결 단계에서 끝나 PG에 닿지 않았다. 연결을 빌리지 못했거나 연결이 거부·시간 초과됐다. 거래가 없다. */
    public static PaymentObservation notSent() {
        return new PaymentObservation(Kind.NOT_SENT, List.of(), null);
    }

    public enum Kind { OBSERVED, UNKNOWN, REJECTED, NOT_SENT }

    public enum UnknownCause {
        /** timeout·연결 실패·5xx. 대답을 듣지 못했고 잠시 뒤 다시 물으면 답할 수 있다. */
        TEMPORARY_FAILURE,
        /** 응답은 받았지만 계약과 달라 쓸 수 없다. 다시 물어도 같을 가능성이 크다. */
        UNEXPECTED_RESPONSE,
        /** 최근 조회 장애가 많아 회로가 막았다. PG에 묻지 않았다. */
        CIRCUIT_OPEN
    }
}
