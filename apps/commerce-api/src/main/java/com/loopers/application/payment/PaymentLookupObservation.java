package com.loopers.application.payment;

import java.util.List;

public sealed interface PaymentLookupObservation {

    // 조회한 거래 목록을 그대로 넘긴다. 빈 목록은 0건이고, 판정은 결과 반영 서비스가 한다.
    record Found(List<PgTransaction> transactions) implements PaymentLookupObservation {
        public Found {
            transactions = List.copyOf(transactions);
        }
    }

    record NotFound() implements PaymentLookupObservation {}

    record Unavailable(String reason, FailureKind kind) implements PaymentLookupObservation {
        public Unavailable(String reason) {
            this(reason, FailureKind.OTHER);
        }
    }

    enum FailureKind {
        TEMPORARY_HTTP, TIMEOUT, NETWORK, HTTP_ERROR, INVALID_RESPONSE,
        CANCELLED, OTHER
    }
}
