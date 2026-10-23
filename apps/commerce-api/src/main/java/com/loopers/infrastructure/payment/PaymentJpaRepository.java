package com.loopers.infrastructure.payment;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, Long> {
    Optional<PaymentJpaEntity> findByOrderId(long orderId);

    Optional<PaymentJpaEntity> findByProviderOrderId(String providerOrderId);

    Optional<PaymentJpaEntity> findByPaymentAttemptKey(String paymentAttemptKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentJpaEntity p where p.orderId = :orderId")
    Optional<PaymentJpaEntity> findByOrderIdForUpdate(@Param("orderId") long orderId);
}
