package com.loopers.infrastructure.order.persistence;

import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.enums.OrderOutboxStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderConfirmedOutboxJpaRepository extends JpaRepository<OrderConfirmedOutbox, Long> {
    Optional<OrderConfirmedOutbox> findByOrderId(Long orderId);

    Optional<OrderConfirmedOutbox> findByEventId(String eventId);

    List<OrderConfirmedOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(OrderOutboxStatus status, ZonedDateTime now, Limit limit);
}
