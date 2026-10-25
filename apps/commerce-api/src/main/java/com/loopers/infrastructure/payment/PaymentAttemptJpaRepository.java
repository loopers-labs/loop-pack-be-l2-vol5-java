package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictReason;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttemptJpaEntity, Long> {

    Optional<PaymentAttemptJpaEntity> findByPaymentAttemptKey(String paymentAttemptKey);

    Optional<PaymentAttemptJpaEntity> findByOrderId(Long orderId);

    List<PaymentAttemptJpaEntity> findAllByOrderIdIn(Collection<Long> orderIds);

    Optional<PaymentAttemptJpaEntity> findByProviderOrderId(String providerOrderId);

    // 허용 상태이고 거래 키가 없거나 같은 키일 때만 상태와 거래 키를 같은 UPDATE로 저장한다. 기록한 키는 바꾸지 않는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE PaymentAttemptJpaEntity a
        SET a.status = :nextStatus,
            a.transactionKey = :transactionKey,
            a.updatedAt = :changedAt
        WHERE a.id = :attemptId
          AND a.status IN :allowedStatuses
          AND (a.transactionKey IS NULL OR a.transactionKey = :transactionKey)
        """)
    int updateStatusWithTransactionKey(
        @Param("attemptId") Long attemptId,
        @Param("allowedStatuses") Collection<PaymentAttemptStatus> allowedStatuses,
        @Param("nextStatus") PaymentAttemptStatus nextStatus,
        @Param("transactionKey") String transactionKey,
        @Param("changedAt") ZonedDateTime changedAt
    );

    // 거래를 확인하지 않은 시도만 바꾼다. 거래 키는 비어 있는 채로 둔다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE PaymentAttemptJpaEntity a
        SET a.status = :nextStatus,
            a.updatedAt = :changedAt
        WHERE a.id = :attemptId
          AND a.status IN :allowedStatuses
          AND a.transactionKey IS NULL
        """)
    int updateStatusWithoutTransaction(
        @Param("attemptId") Long attemptId,
        @Param("allowedStatuses") Collection<PaymentAttemptStatus> allowedStatuses,
        @Param("nextStatus") PaymentAttemptStatus nextStatus,
        @Param("changedAt") ZonedDateTime changedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE PaymentAttemptJpaEntity a
        SET a.conflictReason = :reason,
            a.conflictDetectedAt = :detectedAt
        WHERE a.id = :attemptId
          AND a.conflictReason IS NULL
        """)
    int recordConflict(
        @Param("attemptId") Long attemptId,
        @Param("reason") PaymentConflictReason reason,
        @Param("detectedAt") ZonedDateTime detectedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE PaymentAttemptJpaEntity a
        SET a.lastCheckedAt = :checkedAt
        WHERE a.id = :attemptId
        """)
    int touchLastChecked(@Param("attemptId") Long attemptId, @Param("checkedAt") ZonedDateTime checkedAt);

    @Query("""
        SELECT a FROM PaymentAttemptJpaEntity a
        WHERE a.status IN :unterminatedStatuses
          AND a.updatedAt <= :changedBefore
          AND a.createdAt >= :createdAfter
        ORDER BY a.lastCheckedAt ASC NULLS FIRST, a.id ASC
        """)
    List<PaymentAttemptJpaEntity> findDueForCheck(
        @Param("unterminatedStatuses") Collection<PaymentAttemptStatus> unterminatedStatuses,
        @Param("changedBefore") ZonedDateTime changedBefore,
        @Param("createdAfter") ZonedDateTime createdAfter,
        Pageable pageable
    );
}
