package com.loopers.domain.order;

/**
 * AWAITING_PAYMENT 주문 수를 PG 거래 키 유무로 나눈 것.
 * withTransactionKey: PG 가 접수(PENDING)해 거래 키를 받은 주문. withoutTransactionKey: 거래 키가 없는 주문 — PG 결과를 모르거나(UNKNOWN), 결제 시작 직후 PG 응답을 기다리는 중.
 */
public record AwaitingPaymentCounts(long withTransactionKey, long withoutTransactionKey) {
}
