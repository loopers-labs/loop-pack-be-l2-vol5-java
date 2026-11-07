package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderEventOutboxModel;
import com.loopers.domain.order.OutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderEventOutboxJpaRepository extends JpaRepository<OrderEventOutboxModel, Long> {

    Optional<OrderEventOutboxModel> findByEventId(String eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEventOutboxModel o where o.eventId = :eventId")
    Optional<OrderEventOutboxModel> findByEventIdForUpdate(@Param("eventId") String eventId);

    /** ... where status = ? and next_attempt_at <= ? order by next_attempt_at, id limit ? (IX-15). */
    List<OrderEventOutboxModel> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
        OutboxStatus status, ZonedDateTime now, Pageable pageable);
}
