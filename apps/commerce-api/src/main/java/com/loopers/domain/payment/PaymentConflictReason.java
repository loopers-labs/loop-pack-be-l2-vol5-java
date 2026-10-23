package com.loopers.domain.payment;

/**
 * 충돌 사유 — 기록된 결제 상태와 PG 관찰 결과가 어떻게 어긋났는지(docs/week5/design.md D-011).
 */
public enum PaymentConflictReason {
    /** 종결된 시도에 다른 결과가 확인됐다(예: 관리자 FAILED 처리 뒤 PG SUCCESS). */
    DIFFERENT_OUTCOME_AFTER_TERMINAL,
    /** 같은 providerOrderId로 거래가 2건 이상 조회됐다. 자동으로 하나를 고르지 않는다. */
    MULTIPLE_TRANSACTIONS,
    /** 관찰한 거래의 주문 식별자·금액·거래 키가 우리 기록과 다르다. */
    MISMATCH
}
