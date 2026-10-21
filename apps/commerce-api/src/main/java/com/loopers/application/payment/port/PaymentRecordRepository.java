package com.loopers.application.payment.port;

import com.loopers.domain.payment.PaymentRecord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRecordRepository {
    PaymentRecord save(PaymentRecord payment);

    Optional<PaymentRecord> findByOrderId(long orderId);

    List<PaymentRecord> findByOrderIds(Collection<Long> orderIds);

    Optional<PaymentRecord> findByProviderOrderId(String providerOrderId);

    Optional<PaymentRecord> findByAttemptKey(String attemptKey);
}
