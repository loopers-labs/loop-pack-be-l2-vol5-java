package com.loopers.domain.order.enums;

/** 저장된 확정 사실에서 Outbox 를 잇는 결과 (7주차 D-112) */
public enum OrderBackfillResult {
    /** Outbox 가 없어 PENDING 한 행을 추가함 */
    RECORDED,
    /** 같은 확정 사실의 Outbox 가 이미 있어 아무것도 바꾸지 않음 */
    ALREADY_RECORDED
}
