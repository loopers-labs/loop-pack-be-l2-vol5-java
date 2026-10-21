package com.loopers.payment.adapter.out.pg;

import com.loopers.payment.application.port.out.PgTransaction;
import com.loopers.payment.domain.ExternalPaymentStatus;

/**
 * 로컬 테스트 PG의 거래 응답. 감싸지 않은 객체 {@code {transactionKey, orderId, amount, status, reason}} 그대로다.
 */
record PgTransactionResponse(String transactionKey, String orderId, Long amount, String status, String reason) {

    /**
     * 필수 값이 빠졌거나 모르는 상태면 해석할 수 없다 — IllegalStateException.
     */
    PgTransaction toTransaction() {
        if (isBlank(transactionKey) || isBlank(orderId) || amount == null || isBlank(status)) {
            throw new IllegalStateException("PG 응답에 필수 값이 없습니다");
        }
        try {
            return new PgTransaction(transactionKey, orderId, amount, ExternalPaymentStatus.valueOf(status), reason);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("모르는 PG 상태입니다: " + status, e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
