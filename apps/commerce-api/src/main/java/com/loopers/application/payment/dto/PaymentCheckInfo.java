package com.loopers.application.payment.dto;

/**
 * 결제 결과 확인의 결과. {@code lookupFailed} 이면 PG 를 조회하지 못해 저장된 상태를 그대로 돌려준 것이다. 실패가
 * 아니라 아직 확인하지 못했다는 뜻이다.
 */
public record PaymentCheckInfo(PaymentAttemptInfo info, boolean lookupFailed) {}
