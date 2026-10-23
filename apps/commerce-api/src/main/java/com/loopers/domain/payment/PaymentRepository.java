package com.loopers.domain.payment;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
    Payment save(Payment payment);

    Optional<Payment> findByOrderId(long orderId);

    Optional<Payment> findByOrderIdForUpdate(long orderId);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    Optional<Payment> findByPaymentAttemptKey(UUID paymentAttemptKey);
}
