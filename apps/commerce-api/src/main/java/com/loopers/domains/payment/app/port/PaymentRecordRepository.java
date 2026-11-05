package com.loopers.domains.payment.app.port;

import com.loopers.domains.payment.domain.PaymentRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRecordRepository {
    PaymentRecord save(PaymentRecord payment);

    Optional<PaymentRecord> findByOrderId(long orderId);

    List<PaymentRecord> findByOrderIds(Collection<Long> orderIds);

    Optional<PaymentRecord> findByProviderOrderId(String providerOrderId);

    Optional<PaymentRecord> findByAttemptKey(String attemptKey);

    List<Long> findExpirable(Instant startedAtOrBefore, int limit);
}
