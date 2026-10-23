package com.loopers.application.order;

/**
 * PG 결제 요청 결과. PG 응답 문자열은 infra 가 이 타입으로 바꾸어 돌려준다.
 * NOT_SENT·ERROR·UNKNOWN 은 응답이 없어 transactionKey·amount 가 null 이다.
 */
public record PaymentGatewayResult(Outcome outcome, String transactionKey, Long amount) {

    public enum Outcome {
        /** PG 가 접수했고 결과는 callback 으로 온다. 주문은 AWAITING_PAYMENT 유지. */
        PENDING,
        /** 결제 성공. 주문 CONFIRMED. */
        SUCCESS,
        /** 결제 실패 (4xx 포함). 주문 FAILED, 재고 반환. */
        FAILED,
        /** 요청이 PG 에 닿지 않았음이 확실하다 (연결 실패). 주문 FAILED, 재고 반환. */
        NOT_SENT,
        /**
         * PG 오류(5xx)·응답 지연(read timeout, 본문 수신 끊김 포함). 결제됐는지 모르지만 고객이 기다리는 한계(10초) 안에 결론내려고 실패로 본다. 주문 FAILED, 재고 반환.
         * 실제로 결제됐으면 늦게 온 SUCCESS 는 이미 결론난 주문이라 무시된다.
         */
        ERROR,
        /** 결과를 모른다 (2xx 본문을 읽을 수 없음·거래 조회 실패). 주문 AWAITING_PAYMENT 유지, callback·결제 대기 해소가 결론낸다. */
        UNKNOWN
    }

    public static PaymentGatewayResult notSent() {
        return new PaymentGatewayResult(Outcome.NOT_SENT, null, null);
    }

    public static PaymentGatewayResult error() {
        return new PaymentGatewayResult(Outcome.ERROR, null, null);
    }

    public static PaymentGatewayResult unknown() {
        return new PaymentGatewayResult(Outcome.UNKNOWN, null, null);
    }
}
