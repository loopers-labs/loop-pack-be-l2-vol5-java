package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttempt, Long> {
    Optional<PaymentAttempt> findByOrderId(long orderId);

    List<PaymentAttempt> findByOrderIdIn(List<Long> orderIds);

    Optional<PaymentAttempt> findByAttemptKey(UUID attemptKey);

    Optional<PaymentAttempt> findByProviderAccountAndProviderOrderId(String account, String providerOrderId);

    Optional<PaymentAttempt> findByProviderAccountAndTransactionKey(String account, String transactionKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentAttempt p where p.orderId = :orderId")
    Optional<PaymentAttempt> lockByOrderId(long orderId);
}
