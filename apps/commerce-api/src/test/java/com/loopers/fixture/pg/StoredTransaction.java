package com.loopers.fixture.pg;

/** 카드 정보와 요청 원문은 저장하지 않는다(5.5). */
record StoredTransaction(String transactionKey, String orderId, long amount, PaymentStatus status, String callbackUrl) {

    StoredTransaction withStatus(PaymentStatus newStatus) {
        return new StoredTransaction(transactionKey, orderId, amount, newStatus, callbackUrl);
    }

    TransactionSnapshot toSnapshot() {
        return new TransactionSnapshot(transactionKey, orderId, amount, status);
    }
}
