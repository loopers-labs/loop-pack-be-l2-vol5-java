package com.loopers.infrastructure.order.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderConfirmedOutboxJpaRepository extends JpaRepository<OrderConfirmedOutboxJpaEntity, Long> {
    Optional<OrderConfirmedOutboxJpaEntity> findByEventId(String eventId);

    List<OrderConfirmedOutboxJpaEntity> findAllByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
        OrderConfirmedOutboxStatus status,
        ZonedDateTime nextAttemptAt,
        Pageable pageable
    );
}
