package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.Outbox;
import com.loopers.domain.outbox.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.ZonedDateTime;
import java.util.List;

public interface OutboxJpaRepository extends JpaRepository<Outbox, Long> {
    List<Outbox> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
        OutboxStatus status, ZonedDateTime nextAttemptAt, Pageable pageable
    );
}
