package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
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
    @Query("SELECT a FROM PaymentAttempt a WHERE a.orderId = :orderId")
    Optional<PaymentAttempt> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    Optional<PaymentAttempt> findByAttemptKey(String attemptKey);

    List<PaymentAttempt> findAllByOrderIdIn(Collection<Long> orderIds);

    @Query("SELECT a FROM PaymentAttempt a WHERE a.nextCheckAt <= :now"
        + " AND (a.status IN :unfinished OR (a.status = :watched AND a.watchUntil >= :now))"
        + " ORDER BY a.sendStartedAt ASC, a.id ASC")
    List<PaymentAttempt> findDueForCheck(@Param("unfinished") Collection<PaymentAttemptStatus> unfinished,
                                         @Param("watched") PaymentAttemptStatus watched,
                                         @Param("now") ZonedDateTime now, Pageable pageable);

    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);

    Optional<PaymentAttempt> findByTransactionKey(String transactionKey);
}
