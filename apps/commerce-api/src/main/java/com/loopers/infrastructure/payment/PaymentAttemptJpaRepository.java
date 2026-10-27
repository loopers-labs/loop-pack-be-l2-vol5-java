package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttempt, Long> {

    Optional<PaymentAttempt> findByOrderId(Long orderId);

    Optional<PaymentAttempt> findByUserIdAndAttemptKey(Long userId, String attemptKey);

    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);
}