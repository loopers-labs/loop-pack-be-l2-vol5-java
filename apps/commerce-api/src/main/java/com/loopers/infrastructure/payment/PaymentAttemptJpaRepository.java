package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.UnresolvedPaymentGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttempt, Long> {
    Optional<PaymentAttempt> findByOrderId(Long orderId);

    Optional<PaymentAttempt> findByAttemptKey(String attemptKey);

    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);

    @Query("""
        select new com.loopers.domain.payment.UnresolvedPaymentGroup(a.status, count(a), min(a.createdAt))
        from PaymentAttempt a
        where a.status in :statuses
        group by a.status
        """)
    List<UnresolvedPaymentGroup> summarizeByStatus(@Param("statuses") Collection<PaymentAttemptStatus> statuses);
}
