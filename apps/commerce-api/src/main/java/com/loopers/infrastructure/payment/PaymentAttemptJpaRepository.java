package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttemptModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttemptModel, Long> {
    Optional<PaymentAttemptModel> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentAttemptModel a where a.orderId = :orderId")
    Optional<PaymentAttemptModel> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    Optional<PaymentAttemptModel> findByProviderOrderId(String providerOrderId);

    boolean existsByAttemptKey(String attemptKey);

    List<PaymentAttemptModel> findAllByOrderIdIn(Collection<Long> orderIds);
}
