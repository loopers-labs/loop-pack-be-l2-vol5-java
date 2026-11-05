package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderEventOutboxModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderEventOutboxJpaRepository extends JpaRepository<OrderEventOutboxModel, Long> {

    Optional<OrderEventOutboxModel> findByEventId(String eventId);
}
