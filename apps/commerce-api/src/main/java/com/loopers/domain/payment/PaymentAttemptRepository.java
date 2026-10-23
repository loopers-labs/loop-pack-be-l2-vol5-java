package com.loopers.domain.payment;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository {
    PaymentAttempt save(PaymentAttempt attempt);
    Optional<PaymentAttempt> findByOrderId(Long orderId);
    Optional<PaymentAttempt> findByOrderIdForUpdate(Long orderId);
    Optional<PaymentAttempt> findByAttemptKey(String attemptKey);
    List<PaymentAttempt> findByOrderIds(Collection<Long> orderIds);
    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);

    /** 주문이 아직 PAYMENT_PENDING이고 cutoff 이전에 만든 시도를 id 오름차순으로 afterId 다음부터 limit개 찾는다. */
    List<PaymentAttempt> findUnfinishedCreatedBefore(ZonedDateTime cutoff, Long afterId, int limit);
}
