package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentObservation;

/**
 * directLookupTargetMismatch 는 단건 조회의 요청·응답 거래 키 불일치나 주문별 조회의 다른 주문 거래 포함이다(2.8).
 * 빈 목록·정확한 복수 거래·기대 금액 차이는 해당하지 않는다. pgLookup 실패 집계에만 쓰고 도메인에 노출하지 않는다.
 */
record PgLookupResult(PaymentObservation observation, boolean directLookupTargetMismatch) {
}
