package com.loopers.payment.adapter.out.persistence;

import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentJpaRepository extends JpaRepository<Payment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByAttemptKey(String attemptKey);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    @Query("SELECT p FROM Payment p WHERE p.status IN :statuses "
        + "AND p.nextCheckAt <= :now ORDER BY p.nextCheckAt, p.id")
    List<Payment> findDueForCheck(
        @Param("statuses") Collection<PaymentStatus> statuses,
        @Param("now") ZonedDateTime now,
        Pageable pageable
    );

    @Query("SELECT p.id FROM Payment p WHERE p.status IN :statuses "
        + "AND p.sentAt < :sentBefore ORDER BY p.sentAt, p.id")
    List<Long> findExpirableIds(
        @Param("statuses") Collection<PaymentStatus> statuses,
        @Param("sentBefore") ZonedDateTime sentBefore,
        Pageable pageable
    );
}
