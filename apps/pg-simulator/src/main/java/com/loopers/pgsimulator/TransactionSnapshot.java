package com.loopers.pgsimulator;

/** 저장된 거래 한 건의 불변 값. HTTP 응답에는 callbackUrl 을 뺀 4필드만 쓴다(5.5). 카드 값은 저장하지 않는다. */
public record TransactionSnapshot(String transactionKey, String orderId, long amount, PaymentStatus status,
                                  String callbackUrl) {

    TransactionSnapshot withStatus(PaymentStatus newStatus) {
        return new TransactionSnapshot(transactionKey, orderId, amount, newStatus, callbackUrl);
    }
}
