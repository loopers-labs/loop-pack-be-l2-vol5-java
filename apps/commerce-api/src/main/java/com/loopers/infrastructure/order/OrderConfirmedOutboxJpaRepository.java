package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmedOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderConfirmedOutboxJpaRepository extends JpaRepository<OrderConfirmedOutbox, Long> {

    Optional<OrderConfirmedOutbox> findByEventId(String eventId);

    Optional<OrderConfirmedOutbox> findByOrderId(Long orderId);
}
