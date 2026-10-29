package com.loopers.infrastructure.payment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRecordJpaRepository extends JpaRepository<PaymentRecordJpaEntity, Long> {
    Optional<PaymentRecordJpaEntity> findByOrderId(long orderId);

    List<PaymentRecordJpaEntity> findByOrderIdIn(Collection<Long> orderIds);

    Optional<PaymentRecordJpaEntity> findByProviderOrderId(String providerOrderId);

    Optional<PaymentRecordJpaEntity> findByAttemptKey(String attemptKey);
}
