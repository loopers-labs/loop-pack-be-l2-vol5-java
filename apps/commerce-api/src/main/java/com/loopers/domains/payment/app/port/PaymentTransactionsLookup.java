package com.loopers.domains.payment.app.port;

import java.util.List;

// 0건은 "결제 효과 없음"의 증거가 아니다.
public sealed interface PaymentTransactionsLookup {
    enum Coverage { ALL, SINGLE }

    record Found(List<PaymentTransaction> transactions, Coverage coverage) implements PaymentTransactionsLookup {
        public Found {
            transactions = List.copyOf(transactions);
        }
    }

    record NotCalled() implements PaymentTransactionsLookup {
    }

    // 응답 자체를 받지 못한 실패(연결·타임아웃)와 PG가 오류로 응답한 실패를 나눈다. 환불 추적의 막힌 원인 분류가 이 구분을 쓴다.
    enum FailureKind { UNREACHABLE, ERROR_RESPONSE }

    record Failed(String reason, FailureKind kind) implements PaymentTransactionsLookup {
        public Failed(String reason) {
            this(reason, FailureKind.UNREACHABLE);
        }
    }
}
