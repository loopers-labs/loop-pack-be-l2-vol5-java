package com.loopers.infrastructure.resilience.pay;

import com.loopers.domain.pay.model.PaymentObservation;

/** 집계 제외 결과를 회로 프록시 밖의 fallback까지 전달한다. 응답 원문은 포함하지 않는다. */
public final class IgnoredPaymentLookupException extends RuntimeException {
    private final PaymentObservation observation;

    public IgnoredPaymentLookupException(PaymentObservation observation) {
        super("PG 조회 결과를 회로 집계에서 제외합니다.", null, false, false);
        this.observation = observation;
    }

    public PaymentObservation observation() { return observation; }
}
