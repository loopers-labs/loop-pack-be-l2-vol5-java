package com.loopers.domain.payment;

/** 결제 시도에서 확인한 이상 징후. 기록만 하고 상태·재고는 바꾸지 않는다. 재발송도 하지 않는다. */
public enum PaymentAnomaly {
    /** 조회한 거래의 식별(주문·거래 키)이나 금액이 시도와 다름 */
    MISMATCH,
    /** 같은 주문의 거래가 2건 이상. 하나를 임의로 고르지 않는다 */
    MULTIPLE_TRANSACTIONS,
    /** 거래 키로 조회했는데 거래가 없음 */
    TRANSACTION_NOT_FOUND,
    /** 이미 종결한 결과와 다른 결과가 뒤늦게 도착함 */
    CONFLICTING_RESULT
}
