package com.loopers.application.payment.port;

// NotFound는 "결제 효과 없음"의 증거가 아니다.
public sealed interface PaymentTransactionLookup {
    record Found(PaymentTransaction transaction) implements PaymentTransactionLookup {
    }

    record NotFound() implements PaymentTransactionLookup {
    }

    record Failed(String reason) implements PaymentTransactionLookup {
    }
}
