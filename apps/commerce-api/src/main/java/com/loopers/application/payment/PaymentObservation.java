package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentAttemptStatus;

// 결과 반영 TX B가 받는 관찰이다. 생성 응답과 조회 결과를 같은 반영 규칙으로 다루기 위해 하나로 모은다.
public sealed interface PaymentObservation {

    // 주문 단위 조회에서 찾은 거래 1건이다. 이 시도의 거래인지는 TX B에서 저장된 시도와 비교한다.
    record Transaction(String transactionKey, String providerOrderId, long amount, PaymentAttemptStatus status)
        implements PaymentObservation {}

    // 생성 응답의 접수다. adapter가 요청과 같은 주문·금액인지 이미 확인했다.
    record Accepted(String transactionKey) implements PaymentObservation {}

    record Unknown() implements PaymentObservation {}

    record RejectedBeforeCreate() implements PaymentObservation {}

    record MultipleTransactions() implements PaymentObservation {}
}
