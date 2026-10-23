package com.loopers.infrastructure.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

interface PaymentJpaRepository extends JpaRepository<PaymentEntity, Long> {

    Optional<PaymentEntity> findByAttemptKey(String attemptKey);

    Optional<PaymentEntity> findByOrderId(Long orderId);

    Optional<PaymentEntity> findByProviderOrderId(String providerOrderId);

    @Query("select p.orderId from PaymentEntity p where p.id = :id")
    Optional<Long> findOrderIdById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.id = :id")
    Optional<PaymentEntity> findByIdForUpdate(Long id);
}
