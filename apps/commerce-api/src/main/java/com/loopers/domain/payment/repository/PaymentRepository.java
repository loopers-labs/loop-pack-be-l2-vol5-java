package com.loopers.domain.payment.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import com.loopers.domain.payment.entity.Payment;

public interface PaymentRepository {
    Payment save(Payment payment);

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByAttemptKey(String attemptKey);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    List<Payment> findAllByOrderIds(Collection<Long> orderIds);
}
