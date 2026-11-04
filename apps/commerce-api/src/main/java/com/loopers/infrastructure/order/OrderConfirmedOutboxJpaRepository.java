package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmedOutboxModel;
import com.loopers.domain.order.OrderOutboxStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderConfirmedOutboxJpaRepository extends JpaRepository<OrderConfirmedOutboxModel, Long> {

    Optional<OrderConfirmedOutboxModel> findByEventId(String eventId);

    /** idx_oco_due(status, next_attempt_at, id) 의 조건·정렬과 맞춘 조회. ID 만 읽는다. */
    @Query("""
        select o.id from OrderConfirmedOutboxModel o
        where o.status = :status and o.nextAttemptAt <= :now
        order by o.nextAttemptAt asc, o.id asc
        """)
    List<Long> findDueIds(@Param("status") OrderOutboxStatus status, @Param("now") ZonedDateTime now, Limit limit);
}
