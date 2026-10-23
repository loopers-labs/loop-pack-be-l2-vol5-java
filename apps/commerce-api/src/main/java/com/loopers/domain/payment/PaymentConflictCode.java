package com.loopers.domain.payment;

/** 결제 충돌 요약 코드(3.6). adapter 는 앞의 세 코드만 만들 수 있고 최종 코드는 TX B 가 판정 순서로 다시 정한다. */
public enum PaymentConflictCode {
    MULTIPLE_TRANSACTIONS,
    IDENTITY_MISMATCH,
    AMOUNT_MISMATCH,
    TERMINAL_MISMATCH,
    TERMINAL_REGRESSION,
    TRANSACTION_ALREADY_BOUND
}
