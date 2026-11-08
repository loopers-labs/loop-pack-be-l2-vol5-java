package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxJpaRepository extends JpaRepository<OutboxMessage, Long> {
    Optional<OutboxMessage> findByEventId(String eventId);

    @Query("select o.id from OutboxMessage o where o.status = :status and o.nextAttemptAt <= :now "
        + "order by o.nextAttemptAt asc, o.id asc")
    List<Long> findDueIds(OutboxStatus status, Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboxMessage o where o.id = :id")
    Optional<OutboxMessage> findByIdForUpdate(Long id);
}
