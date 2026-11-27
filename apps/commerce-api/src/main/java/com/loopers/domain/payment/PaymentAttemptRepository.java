package com.loopers.domain.payment;

import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository {
    PaymentAttempt save(PaymentAttempt attempt);

    /** 주문의 결제 시도를 조회한다. 주문당 한 건이다. */
    Optional<PaymentAttempt> findByOrderId(Long orderId);

    /** 고객이 보낸 시도 키로 조회한다. 같은 키가 다른 주문에 쓰였는지 확인하는 데 쓴다. */
    Optional<PaymentAttempt> findByAttemptKey(String attemptKey);

    /** PG 요청·조회에 쓰는 주문 식별자로 조회한다. callback 이 시도를 찾는 데 쓴다. */
    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);

    /** 미종결 상태별 시도 수와 가장 오래된 생성 시각. 시도가 없는 상태는 결과에 없다. */
    List<UnresolvedPaymentGroup> summarizeUnresolved();
}
