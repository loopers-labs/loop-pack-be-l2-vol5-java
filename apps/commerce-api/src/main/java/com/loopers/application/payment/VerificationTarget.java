package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentCheckToken;

/** 확인 준비 결과. 거래 키가 있으면 단건 조회, 없으면 외부 주문 ID 로 주문별 조회 한 번을 한다(설계 4.3). */
public record VerificationTarget(PaymentCheckToken token, LookupKind kind, String target) {

    public enum LookupKind {
        TRANSACTION_KEY,
        PROVIDER_ORDER_ID
    }
}
