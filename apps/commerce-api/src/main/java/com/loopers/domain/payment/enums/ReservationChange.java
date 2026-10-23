package com.loopers.domain.payment.enums;

/** 전이가 요구하는 주문 · 재고 후속 효과. 종결된 시도에는 항상 NONE 이라 중복 사건이 재고를 바꾸지 않음 */
public enum ReservationChange {
    NONE,
    /** 재고는 추가로 차감하지 않음 */
    CONSUME,
    /** 품목 수량만큼 재고 반환 */
    RELEASE
}
