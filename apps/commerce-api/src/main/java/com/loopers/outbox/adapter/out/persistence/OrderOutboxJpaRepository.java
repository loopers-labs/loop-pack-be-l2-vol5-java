package com.loopers.outbox.adapter.out.persistence;

import com.loopers.outbox.domain.OrderOutbox;
import com.loopers.outbox.domain.OrderOutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderOutboxJpaRepository extends JpaRepository<OrderOutbox, Long> {

    Optional<OrderOutbox> findByEventId(String eventId);

    /**
     * 인덱스 idx_order_outbox_due(status, next_attempt_at, id)로 차례가 된 행만 범위로 읽는다 (D-0 인덱스).
     */
    @Query("SELECT o FROM OrderOutbox o WHERE o.status IN :statuses AND o.nextAttemptAt <= :now ORDER BY o.id")
    List<OrderOutbox> findDue(
        @Param("statuses") Collection<OrderOutboxStatus> statuses,
        @Param("now") ZonedDateTime now,
        Pageable pageable
    );
}
