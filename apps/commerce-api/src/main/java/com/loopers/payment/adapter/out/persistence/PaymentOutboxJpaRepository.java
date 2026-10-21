package com.loopers.payment.adapter.out.persistence;

import com.loopers.payment.domain.PaymentOutbox;
import com.loopers.payment.domain.PaymentOutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 장부 상태 전이는 모두 "WHERE status = 기대 상태" 조건부 UPDATE다. 영향 행 수 1이면 이번 실행이 이겼다 (ADR-W5-01·03).
 * 호출자 트랜잭션이 있으면 거기에 참여하고(claim + payment SENDING을 한 TX로), 없으면 이 메서드가 연다.
 */
public interface PaymentOutboxJpaRepository extends JpaRepository<PaymentOutbox, Long> {

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PaymentOutbox o SET o.status = :to, o.claimedAt = :now, o.updatedAt = :now "
        + "WHERE o.paymentId = :paymentId AND o.status = :from")
    int claim(
        @Param("paymentId") Long paymentId,
        @Param("from") PaymentOutboxStatus from,
        @Param("to") PaymentOutboxStatus to,
        @Param("now") ZonedDateTime now
    );

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PaymentOutbox o SET o.status = :to, o.completedAt = :now, o.updatedAt = :now "
        + "WHERE o.paymentId = :paymentId AND o.status = :from")
    int complete(
        @Param("paymentId") Long paymentId,
        @Param("from") PaymentOutboxStatus from,
        @Param("to") PaymentOutboxStatus to,
        @Param("now") ZonedDateTime now
    );

    @Query("SELECT o.paymentId FROM PaymentOutbox o WHERE o.status = :status AND o.createdAt < :before ORDER BY o.createdAt, o.id")
    List<Long> findPaymentIdsCreatedBefore(
        @Param("status") PaymentOutboxStatus status,
        @Param("before") ZonedDateTime before,
        Pageable pageable
    );

    @Query("SELECT o.paymentId FROM PaymentOutbox o WHERE o.status = :status AND o.claimedAt < :before ORDER BY o.claimedAt, o.id")
    List<Long> findPaymentIdsClaimedBefore(
        @Param("status") PaymentOutboxStatus status,
        @Param("before") ZonedDateTime before,
        Pageable pageable
    );
}
