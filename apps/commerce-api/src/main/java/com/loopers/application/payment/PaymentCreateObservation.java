package com.loopers.application.payment;

public sealed interface PaymentCreateObservation {

    record Accepted(String transactionKey) implements PaymentCreateObservation {}

    // 거래를 만들기 전에 끝난 확정 거절이다. 이 요청으로는 나중에도 거래가 생기지 않는다.
    record RejectedNoEffect() implements PaymentCreateObservation {}

    // timeout·연결 유실·해석할 수 없는 응답이다. 거래 생성 여부를 알 수 없다.
    record Unknown(String reason) implements PaymentCreateObservation {}
}
