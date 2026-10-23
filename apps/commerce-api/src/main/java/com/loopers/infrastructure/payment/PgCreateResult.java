package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;

/** directCreateResponseMismatch 는 생성 응답의 주문·금액이 요청과 다른 직접 계약 위반 표시이며 pgCreate 실패 집계에 쓴다(2.8). */
record PgCreateResult(PaymentObservation observation, boolean directCreateResponseMismatch) {
}
