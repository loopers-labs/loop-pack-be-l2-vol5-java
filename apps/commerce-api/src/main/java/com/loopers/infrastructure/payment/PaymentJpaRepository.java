package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface PaymentJpaRepository extends JpaRepository<PaymentEntity, Long> {

    String SKIP_LOCKED = "-2";

    Optional<PaymentEntity> findByAttemptKey(String attemptKey);

    Optional<PaymentEntity> findByOrderId(Long orderId);

    Optional<PaymentEntity> findByProviderOrderId(String providerOrderId);

    @Query("select p.status, count(p), min(p.requestedAt) from PaymentEntity p where p.status in :statuses group by p.status")
    List<Object[]> summarizeByStatus(Collection<PaymentStatus> statuses);

    @Query("select p.orderId from PaymentEntity p where p.id = :id")
    Optional<Long> findOrderIdById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.id = :id")
    Optional<PaymentEntity> findByIdForUpdate(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query("""
        select p from PaymentEntity p
        where p.status in :statuses
          and p.requestedAt < :requestedBefore
          and (p.lastCheckedAt is null or p.lastCheckedAt < :checkedBefore)
        order by p.lastCheckedAt asc nulls first, p.requestedAt asc
        """)
    List<PaymentEntity> findDueForCheck(Collection<PaymentStatus> statuses, Instant requestedBefore,
                                        Instant checkedBefore, Pageable page);
}
