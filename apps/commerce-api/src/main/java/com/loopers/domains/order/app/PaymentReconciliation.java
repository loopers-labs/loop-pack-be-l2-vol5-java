package com.loopers.domains.order.app;

// assessment는 잠금 아래에서 판정하지 않고 돌아온 경우(결제 기록의 PG가 바뀜 등) null이다.
public record PaymentReconciliation(OrderResult result, PaymentLookupAssessment assessment) { }
