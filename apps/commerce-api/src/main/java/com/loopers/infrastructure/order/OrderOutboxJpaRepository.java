package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderOutboxModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderOutboxJpaRepository extends JpaRepository<OrderOutboxModel, Long> {
    Optional<OrderOutboxModel> findByEventId(String eventId);

    List<OrderOutboxModel> findAllByOrderId(Long orderId);
}
