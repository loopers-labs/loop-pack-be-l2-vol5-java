package com.loopers.pgsimulator;

/**
 * 생성 요청의 결과. PENDING_THEN_* 는 202 PENDING 으로 응답하고, 자동 처리가 켜져 있을 때만 지연 뒤 뒤의 상태로 바뀐다.
 * REJECT_BEFORE_CREATE 는 거래 저장·자동 처리 예약 없이 5.5 의 422 를 반환한다.
 */
public enum CreateOutcome {
    PENDING_THEN_SUCCESS,
    PENDING_THEN_FAILED,
    IMMEDIATE_SUCCESS,
    REJECT_BEFORE_CREATE
}
