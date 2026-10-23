package com.loopers.domain.payment.enums;

/** 결제 상태를 바꾸지 않고 남기는 충돌 사유 (D-65) */
public enum PaymentConflict {
    /** 같은 주문의 거래가 2건 이상 */
    MULTIPLE_TRANSACTIONS,
    /** 주문 · 금액 불일치, 또는 저장된 거래 키가 목록에 없음 */
    MISMATCHED_TRANSACTION,
    /** 거래 키를 아는데 조회 결과가 0건 */
    MISSING_KNOWN_TRANSACTION,
    /** 처리 중으로 확인한 뒤 다른 거래 키 · 효과 0건 거절 생성 응답이 늦게 도착함 */
    LATE_CREATE_CONTRADICTION,
    /** 종결 상태와 상충하는 조회 결과 */
    TERMINAL_CONTRADICTION
}
