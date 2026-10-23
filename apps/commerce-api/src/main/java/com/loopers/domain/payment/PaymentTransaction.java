package com.loopers.domain.payment;

/**
 * PG 생성 응답 또는 권위 있는 조회의 내부 관찰 값이다. callback 요청 DTO가 아니다.
 * 생성자는 기본 값의 유효성만 검사하며 HTTP 계약·관찰 출처는 adapter에서 검증해야 한다.
 */
public record PaymentTransaction(String providerAccount, String providerOrderId, String transactionKey,
                                 long amount, Status status) {
    public PaymentTransaction {
        if (providerAccount == null || providerAccount.isBlank() || providerOrderId == null || providerOrderId.isBlank()
            || transactionKey == null || transactionKey.isBlank() || amount < 0 || status == null) {
            throw new IllegalArgumentException("Payment transaction requires identities, amount and status");
        }
    }

    public enum Status {
        PENDING,
        SUCCESS,
        FAILED
    }
}
