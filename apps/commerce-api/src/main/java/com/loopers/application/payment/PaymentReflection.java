package com.loopers.application.payment;

// 처리 결과와 TX B가 끝난 뒤 새 조회 경계에서 읽은 저장 상태다.
public record PaymentReflection(PaymentReflectionOutcome outcome, PaymentStatusInfo payment) {}
