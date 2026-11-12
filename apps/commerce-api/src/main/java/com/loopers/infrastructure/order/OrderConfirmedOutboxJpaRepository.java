package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderConfirmedOutboxJpaRepository extends JpaRepository<OrderConfirmedOutbox, Long> {

    Optional<OrderConfirmedOutbox> findByEventId(String eventId);

    @Query("""
        SELECT o FROM OrderConfirmedOutbox o
        WHERE o.status = :status AND o.nextAttemptAt <= :before
        ORDER BY o.createdAt ASC
        LIMIT :limit
        """)
    List<OrderConfirmedOutbox> findPendingToProcess(
        @Param("status") OutboxStatus status,
        @Param("before") ZonedDateTime before,
        @Param("limit") int limit
    );
}
