package com.loopers.domain.payment;

/**
 * PG가 알려 준 거래 한 건이다. 형식만 검증된 값이고, 이 결제 시도의 거래인지(주문·금액·거래 키 일치)는 도메인이 따로 대조한다.
 */
public record PgTransaction(String transactionKey, String providerOrderId, long amount, PgTransactionStatus status) {
}
