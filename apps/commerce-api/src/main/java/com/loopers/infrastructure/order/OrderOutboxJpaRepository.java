package com.loopers.infrastructure.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OrderOutboxJpaRepository extends JpaRepository<OrderOutboxEntity, String> {
    @Modifying
    @Query("update OrderOutboxEntity o set o.status = 'BLOCKED', o.nextAttemptAt = null,"
            + " o.lastError = 'AttemptOutcomeUnknown'"
            + " where o.status = 'PENDING' and o.attemptCountInCycle >= :maxAttempts"
            + " and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)")
    int blockExpiredAttempts(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query("update OrderOutboxEntity o set o.status = 'PENDING', o.attemptCountInCycle = 0,"
            + " o.nextAttemptAt = :now where o.eventId = :eventId and o.status = 'BLOCKED'")
    int resumeBlocked(@Param("eventId") String eventId, @Param("now") Instant now);

    @Query("select o.eventId from OrderOutboxEntity o"
            + " where o.status = 'PENDING' and o.nextAttemptAt <= :now and o.attemptCountInCycle < :maxAttempts"
            + " order by o.createdAt, o.eventId")
    List<String> readyEventIds(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts, Pageable page);

    @Modifying
    @Query("delete from OrderOutboxEntity o where o.status = 'PUBLISHED' and o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
