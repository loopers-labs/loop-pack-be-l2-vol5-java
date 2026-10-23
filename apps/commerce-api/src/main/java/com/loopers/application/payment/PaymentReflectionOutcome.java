package com.loopers.application.payment;

public enum PaymentReflectionOutcome {
    // 시도 상태를 바꿨다. 종결이면 주문과 재고도 함께 반영했다.
    APPLIED,
    // 이미 종결됐거나 중복·늦은 관찰, 거래 0건·404처럼 바꿀 것이 없다.
    UNCHANGED,
    // 결과를 판정할 수 없는 관찰이다. 처음 사유만 기록하고 상태는 바꾸지 않는다.
    CONFLICT,
    // PG 조회가 실패해 상태를 바꾸지 않았다. callback은 재전송을 요청한다.
    LOOKUP_UNAVAILABLE
}
