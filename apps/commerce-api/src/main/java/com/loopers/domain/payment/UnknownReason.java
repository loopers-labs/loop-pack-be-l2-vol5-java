package com.loopers.domain.payment;

/**
 * 생성 결과를 모르게 된 이유. 운영 기록용이며, 상태 판단은 이유에 따라 갈리지 않는다.
 * 모든 이유가 같은 처리(예약 유지, 재발송 없음, 기존 거래 조회)로 이어진다.
 */
public enum UnknownReason {
    /** 응답을 기다리다 시간이 다 됐다. 요청이 도착해 거래가 생겼을 수 있다. */
    TIMEOUT,
    /** 연결을 맺지 못했거나 응답 전에 끊겼다. */
    CONNECTION_LOST,
    /** 계약이 정한 응답(202, 정확한 422)이 아니다. */
    UNEXPECTED_STATUS,
    /** 본문이 JSON이 아니거나 필드 타입이 계약과 다르다. */
    INVALID_BODY,
    /** 응답이 돌려준 주문 번호·금액이 요청과 다르다. 다른 거래의 응답일 수 있다. */
    ECHO_MISMATCH
}
