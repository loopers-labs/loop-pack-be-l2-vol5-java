package com.loopers.domain.payment;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository {
    PaymentAttemptModel save(PaymentAttemptModel attempt);

    Optional<PaymentAttemptModel> findByOrderId(Long orderId);

    Optional<PaymentAttemptModel> findByPaymentAttemptKey(String key);

    List<PaymentAttemptModel> findPendingBefore(ZonedDateTime sentBefore);

    List<PaymentAttemptModel> findUnknownBefore(ZonedDateTime sentBefore);
}
