package com.loopers.payment.domain;

/**
 * PG가 알려 준 거래 상태 (과제 2절). 생성 수락(PENDING)과 최종 결과(SUCCESS·FAILED)를 구분한다.
 */
public enum ExternalPaymentStatus {
    PENDING,
    SUCCESS,
    FAILED
}
