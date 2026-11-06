package com.loopers.domain.payment;

/** 거래를 조회하지 못한 이유. 운영 기록용이며, 어떤 이유도 결제를 실패로 확정하는 근거가 아니다. */
public enum UnavailableReason {
    TIMEOUT,
    CONNECTION_LOST,
    /** 5xx 응답. */
    SERVER_ERROR,
    /** 404 외의 4xx 등 계약에 없는 응답. 우리 요청의 문제일 수 있어 다시 묻지 않는다. */
    UNEXPECTED_STATUS,
    /** 본문이 JSON이 아니거나, 필드 타입·상태 값이 계약과 다르거나, 목록이 배열이 아니다. */
    INVALID_BODY,
    /** 회로 차단기가 열려 있어 PG에 묻지 않았다. */
    CIRCUIT_OPEN
}
