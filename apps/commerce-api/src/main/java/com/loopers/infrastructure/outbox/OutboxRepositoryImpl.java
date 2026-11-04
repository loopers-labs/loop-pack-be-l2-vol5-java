package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.Outbox;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OutboxRepositoryImpl implements OutboxRepository {
    private final OutboxJpaRepository outboxJpaRepository;

    @Override
    public Outbox save(Outbox outbox) {
        return outboxJpaRepository.save(outbox);
    }

    @Override
    public Optional<Outbox> findById(Long id) {
        return outboxJpaRepository.findById(id);
    }

    @Override
    public List<Outbox> findDueForDelivery(ZonedDateTime now, int limit) {
        return outboxJpaRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            OutboxStatus.PENDING, now, PageRequest.of(0, limit)
        );
    }
}
