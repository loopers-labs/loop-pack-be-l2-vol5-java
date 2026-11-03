package com.loopers.infrastructure.order.persistence;

import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderConfirmedOutboxJpaRepository extends JpaRepository<OrderConfirmedOutbox, Long> {
    Optional<OrderConfirmedOutbox> findByOrderId(Long orderId);
}
