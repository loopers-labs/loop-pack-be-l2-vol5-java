package com.loopers.domain.payment.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.enums.PaymentStatus;

public interface PaymentRepository {
    Payment save(Payment payment);

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByAttemptKey(String attemptKey);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    List<Payment> findAllByOrderIds(Collection<Long> orderIds);

    /** 종결되지 않은 상태마다 결제 시도 수. 0건인 상태도 0 으로 담음 */
    Map<PaymentStatus, Long> countUnresolvedByStatus();
}
