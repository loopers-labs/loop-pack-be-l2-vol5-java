package com.loopers.domain.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findByAttemptKey(PaymentAttemptKey attemptKey);

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByProviderOrderId(ProviderOrderId providerOrderId);

    Optional<Long> findOrderIdById(Long paymentId);

    Optional<Payment> findByIdForUpdate(Long paymentId);

    List<Payment> findDueForCheckSkippingLocked(Instant requestedBefore, Instant checkedBefore, int limit);

    List<Unresolved> summarizeUnresolved();

    record Unresolved(PaymentStatus status, long count, Instant oldestRequestedAt) {
    }
}
