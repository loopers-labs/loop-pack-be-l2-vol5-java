package com.loopers.domain.payment;

import java.util.Optional;

public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findByAttemptKey(PaymentAttemptKey attemptKey);

    Optional<Payment> findByOrderId(Long orderId);
}
