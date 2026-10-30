package com.loopers.payment.domain;

/**
 * 결과 반영 판단 (plan.md 4-1, ADR-W5-04). 중복은 정상 흐름이라 예외가 아니라 이 값으로 알린다.
 */
public enum PaymentApplyOutcome {
    /** 이번 호출이 종결로 옮겼다. 호출자는 이제 주문·재고 반영을 한다 */
    APPLIED,
    /** 기록했지만 종결이 아니다 (PENDING·UNKNOWN) */
    NOT_FINAL,
    /** 중복·늦은 도착. 아무것도 바꾸지 않았다 */
    ALREADY_APPLIED,
    /** 종결과 반대 결과 등 모순. 상태 유지 + 이상 기록 */
    CONFLICT,
    /** 관찰의 식별자·금액이 이 결제와 다르다. 상태 유지 + 이상 기록 */
    MISMATCH
}
