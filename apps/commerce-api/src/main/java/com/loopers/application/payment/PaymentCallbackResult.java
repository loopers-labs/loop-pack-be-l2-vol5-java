package com.loopers.application.payment;

// PG 결과 알림의 처리 결과다. HTTP 응답 변환은 callback 컨트롤러가 맡는다.
public enum PaymentCallbackResult {
    // 반영·중복·늦은 관찰·충돌 기록·미확인 유지. 다시 받아도 판정이 같다.
    PROCESSED,
    // PG 조회 불가나 결과 반영 rollback 같은 일시 장애다. 재전송되면 같은 경로로 다시 처리한다.
    RETRY_LATER
}
