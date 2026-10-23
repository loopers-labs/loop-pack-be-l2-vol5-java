package com.loopers.application.payment;

// PG와의 HTTP 형식은 infrastructure adapter가 맡고, application은 관찰 결과만 받는다.
public interface PaymentGateway {

    // 생성 요청은 정확히 한 번만 보낸다. 응답을 해석할 수 없으면 UNKNOWN으로 넘긴다.
    PaymentCreateObservation create(PaymentCreateCommand command);

    PaymentLookupObservation lookup(String transactionKey);

    PaymentLookupObservation lookupByOrder(String providerOrderId);
}
