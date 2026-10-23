package com.loopers.domain.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository {
    PaymentAttempt save(PaymentAttempt attempt);

    Optional<PaymentAttempt> findByOrderId(long orderId);

    List<PaymentAttempt> findByOrderIds(List<Long> orderIds);

    Optional<PaymentAttempt> findByAttemptKey(UUID attemptKey);

    Optional<PaymentAttempt> findByProviderOrderId(String account, String providerOrderId);

    Optional<PaymentAttempt> findByTransactionKey(String account, String transactionKey);

    /** 호출자가 주문 잠금을 먼저 확보한 동일 트랜잭션 안에서 사용한다. */
    Optional<PaymentAttempt> lockByOrderId(long orderId);
}
