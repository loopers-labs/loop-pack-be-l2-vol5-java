package com.loopers.domains.order.out;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderConfirmedOutboxJpaRepository extends JpaRepository<OrderConfirmedOutboxJpaEntity, Long> {
    @Query("select e from OrderConfirmedOutboxJpaEntity e where e.status = 'PENDING' and e.nextAttemptAt <= :now"
        + " order by e.nextAttemptAt, e.id")
    List<OrderConfirmedOutboxJpaEntity> findDue(@Param("now") Instant now, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderConfirmedOutboxJpaEntity e set e.status = 'PUBLISHED', e.publishedAt = :publishedAt"
        + " where e.id = :id and e.status = 'PENDING'")
    int markPublished(@Param("id") long id, @Param("publishedAt") Instant publishedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderConfirmedOutboxJpaEntity e set e.attemptCount = e.attemptCount + 1, e.nextAttemptAt = :nextAttemptAt,"
        + " e.lastError = :error where e.id = :id and e.status = 'PENDING'")
    int markBrokerFailure(@Param("id") long id, @Param("nextAttemptAt") Instant nextAttemptAt, @Param("error") String error);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderConfirmedOutboxJpaEntity e set e.attemptCount = e.attemptCount + 1,"
        + " e.rowFailureCount = e.rowFailureCount + 1, e.nextAttemptAt = :nextAttemptAt, e.lastError = :error,"
        + " e.status = :status where e.id = :id and e.status = 'PENDING'")
    int markRowFailure(@Param("id") long id, @Param("nextAttemptAt") Instant nextAttemptAt, @Param("error") String error,
        @Param("status") String status);
}
