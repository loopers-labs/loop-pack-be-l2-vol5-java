package com.loopers.infrastructure.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

interface OrderEventOutboxJpaRepository extends JpaRepository<OrderEventOutboxEntity, Long> {

    Optional<OrderEventOutboxEntity> findByEventId(String eventId);

    @Query(value = """
        select * from order_event_outbox
        where status = 'PENDING' and next_attempt_at <= utc_timestamp(6)
        order by id
        limit :limit
        """, nativeQuery = true)
    List<OrderEventOutboxEntity> findDue(int limit);

    @Modifying
    @Query(value = """
        update order_event_outbox
        set status = 'PUBLISHED', attempt_count = attempt_count + 1,
            published_at = utc_timestamp(6), updated_at = utc_timestamp(6)
        where id in (:ids) and status = 'PENDING'
        """, nativeQuery = true)
    int markPublished(List<Long> ids);

    @Modifying
    @Query(value = """
        update order_event_outbox
        set status = 'BLOCKED', attempt_count = attempt_count + 1,
            last_error = :error, updated_at = utc_timestamp(6)
        where id = :id and status = 'PENDING'
        """, nativeQuery = true)
    int markBlocked(Long id, String error);

    @Modifying
    @Query(value = """
        update order_event_outbox
        set attempt_count = attempt_count + 1,
            next_attempt_at = utc_timestamp(6) + interval :delayMicros microsecond,
            last_error = :error, updated_at = utc_timestamp(6)
        where id = :id and status = 'PENDING'
        """, nativeQuery = true)
    int retryLater(Long id, long delayMicros, String error);

    @Modifying
    @Query(value = """
        update order_event_outbox
        set status = 'PENDING', next_attempt_at = utc_timestamp(6), updated_at = utc_timestamp(6)
        where event_id = :eventId and status = 'BLOCKED'
        """, nativeQuery = true)
    int resume(String eventId);
}
