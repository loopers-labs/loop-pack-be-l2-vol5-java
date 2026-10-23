package com.loopers.application.payment;

// TX A의 결과다. 이번 요청이 시도를 새로 commit한 경우에만 PG 생성 권한이 생긴다.
public sealed interface PaymentReservation {

    record Started(Long attemptId, String providerOrderId, long amount) implements PaymentReservation {}

    // 같은 키의 재전송이거나 경쟁에서 졌다. 새 조회 경계에서 저장된 시도로 응답을 정한다.
    record AlreadyRequested() implements PaymentReservation {}
}
