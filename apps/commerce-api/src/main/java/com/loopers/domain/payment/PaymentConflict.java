package com.loopers.domain.payment;

/** 관찰을 적용하지 않고 기록만 남긴 이유. 상태는 바꾸지 않으며, 사람이 확인할 대상이다. */
public enum PaymentConflict {
    /** 같은 주문 번호의 거래가 2건 이상이다. 첫 거래를 고르지 않는다. */
    MULTIPLE_TRANSACTIONS,
    /** 조회한 거래의 주문 번호가 이 시도의 것이 아니다. */
    ORDER_MISMATCH,
    /** 조회한 거래의 금액이 이 시도의 금액과 다르다. */
    AMOUNT_MISMATCH,
    /** 조회한 거래의 키가 이 시도에 저장된 키와 다르다. */
    TRANSACTION_KEY_MISMATCH,
    /** 이미 종결한 결과와 반대되는 최종 결과를 받았다. 덮어쓰지 않는다. */
    TERMINAL_RESULT_MISMATCH
}
