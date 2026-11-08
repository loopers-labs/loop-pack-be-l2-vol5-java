package com.loopers.infrastructure.order.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

// 상태 전이는 읽은 시도 번호가 DB에 그대로 남아 있을 때만 적용한다.
// 다른 실행이 먼저 시작하거나 갱신했으면 0행이며, 호출자는 건너뛰거나 오래된 결과로 처리한다.
public interface OrderConfirmedOutboxJpaRepository extends JpaRepository<OrderConfirmedOutboxJpaEntity, Long> {
    Optional<OrderConfirmedOutboxJpaEntity> findByEventId(String eventId);

    List<OrderConfirmedOutboxJpaEntity> findAllByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
        OrderConfirmedOutboxStatus status,
        ZonedDateTime nextAttemptAt,
        Pageable pageable
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE OrderConfirmedOutboxJpaEntity o
        SET o.attemptCount = o.attemptCount + 1,
            o.cycleAttemptCount = o.cycleAttemptCount + 1,
            o.nextAttemptAt = :nextAttemptAt,
            o.updatedAt = :changedAt
        WHERE o.eventId = :eventId
          AND o.status = com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxStatus.PENDING
          AND o.cycleAttemptCount = :currentAttempt
          AND o.nextAttemptAt <= :changedAt
        """)
    int beginAttempt(
        @Param("eventId") String eventId,
        @Param("currentAttempt") int currentAttempt,
        @Param("nextAttemptAt") ZonedDateTime nextAttemptAt,
        @Param("changedAt") ZonedDateTime changedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE OrderConfirmedOutboxJpaEntity o
        SET o.status = com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxStatus.PUBLISHED,
            o.publishedAt = :changedAt,
            o.lastError = NULL,
            o.nextAttemptAt = :changedAt,
            o.updatedAt = :changedAt
        WHERE o.eventId = :eventId
          AND o.status = com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxStatus.PENDING
          AND o.cycleAttemptCount = :attempt
        """)
    int markPublished(
        @Param("eventId") String eventId,
        @Param("attempt") int attempt,
        @Param("changedAt") ZonedDateTime changedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE OrderConfirmedOutboxJpaEntity o
        SET o.nextAttemptAt = :nextAttemptAt,
            o.lastError = :error,
            o.updatedAt = :changedAt
        WHERE o.eventId = :eventId
          AND o.status = com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxStatus.PENDING
          AND o.cycleAttemptCount = :attempt
        """)
    int scheduleRetry(
        @Param("eventId") String eventId,
        @Param("attempt") int attempt,
        @Param("nextAttemptAt") ZonedDateTime nextAttemptAt,
        @Param("error") String error,
        @Param("changedAt") ZonedDateTime changedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE OrderConfirmedOutboxJpaEntity o
        SET o.status = com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxStatus.BLOCKED,
            o.nextAttemptAt = :changedAt,
            o.lastError = :error,
            o.updatedAt = :changedAt
        WHERE o.eventId = :eventId
          AND o.status = com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxStatus.PENDING
          AND o.cycleAttemptCount = :attempt
        """)
    int markBlocked(
        @Param("eventId") String eventId,
        @Param("attempt") int attempt,
        @Param("error") String error,
        @Param("changedAt") ZonedDateTime changedAt
    );
}
