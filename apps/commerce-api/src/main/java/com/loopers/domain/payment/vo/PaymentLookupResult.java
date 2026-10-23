package com.loopers.domain.payment.vo;

import java.util.List;

/** 거래 목록의 판정은 Payment 가 함 */
public sealed interface PaymentLookupResult {

    /** 빈 목록도 정상 결과 */
    record Found(List<ProviderTransaction> transactions) implements PaymentLookupResult {
        public Found {
            transactions = List.copyOf(transactions);
        }
    }

    /** timeout · 5xx · 회로 OPEN */
    record Unavailable() implements PaymentLookupResult {}
}
