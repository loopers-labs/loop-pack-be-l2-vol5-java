package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderOutbox;
import com.loopers.domain.order.OrderOutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderOutboxJpaRepository extends JpaRepository<OrderOutbox, Long> {
    Optional<OrderOutbox> findByEventId(String eventId);

    List<OrderOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
        OrderOutboxStatus status, Instant nextAttemptAt, Pageable pageable);
}
