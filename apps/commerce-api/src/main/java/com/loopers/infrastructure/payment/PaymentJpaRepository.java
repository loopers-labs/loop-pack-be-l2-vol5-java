package com.loopers.infrastructure.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface PaymentJpaRepository extends JpaRepository<PaymentEntity, Long> {

    Optional<PaymentEntity> findByAttemptKey(String attemptKey);

    Optional<PaymentEntity> findByOrderId(Long orderId);
}
