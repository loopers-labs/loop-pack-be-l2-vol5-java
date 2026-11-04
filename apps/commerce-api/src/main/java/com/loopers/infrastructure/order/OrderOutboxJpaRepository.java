package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderOutboxJpaRepository extends JpaRepository<OrderOutboxModel, Long> {
    Optional<OrderOutboxModel> findByEventId(String eventId);

    List<OrderOutboxModel> findAllByOrderId(Long orderId);

    @Query("select o.eventId from OrderOutboxModel o where o.status = :status and o.nextAttemptAt <= :now order by o.nextAttemptAt, o.id")
    List<String> findDueEventIds(@Param("status") OrderOutboxStatus status, @Param("now") Instant now, Limit limit);
}
