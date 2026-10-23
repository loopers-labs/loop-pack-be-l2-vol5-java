package com.loopers.domain.payment;

/**
 * PG 가 보관하는 거래 한 건. PG 의 orderId 는 우리 쪽 providerOrderId 다. 카드 번호는 담지 않는다.
 */
public record PaymentTransaction(String transactionKey, String providerOrderId, long amount, Status status) {

    public enum Status {
        PENDING, SUCCESS, FAILED
    }
}