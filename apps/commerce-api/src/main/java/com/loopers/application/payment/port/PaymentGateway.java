package com.loopers.application.payment.port;

// 어댑터는 PG 응답을 분류해 돌려줄 뿐 재시도·fallback·다른 PG 호출을 하지 않는다.
// 재조회·실패 확정·서브 PG 전환은 호출하는 애플리케이션이 정한다(설계 21절).
public interface PaymentGateway {
    String providerId();

    // PG가 멱등 키를 받지 않으므로 한 번만 보낸다. 결과를 모르면 Unknown을 돌려주고 다시 보내지 않는다.
    PaymentCreateResult create(PaymentCreateCommand command);

    PaymentTransactionsLookup verifyOrder(String providerOrderId, String storedTransactionKey);
}
