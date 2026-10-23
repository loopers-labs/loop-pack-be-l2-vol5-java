package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.order.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttempt, Long> {
    Optional<PaymentAttempt> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentAttempt a where a.orderId = :orderId")
    Optional<PaymentAttempt> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    Optional<PaymentAttempt> findByAttemptKey(String attemptKey);

    List<PaymentAttempt> findByOrderIdIn(Collection<Long> orderIds);

    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);

    @Query("select a from PaymentAttempt a, OrderModel o where o.id = a.orderId and o.status = :status"
        + " and a.createdAt <= :cutoff and a.id > :afterId order by a.id")
    List<PaymentAttempt> findByOrderStatusCreatedBefore(@Param("status") OrderStatus status,
        @Param("cutoff") ZonedDateTime cutoff, @Param("afterId") Long afterId, Pageable pageable);
}
