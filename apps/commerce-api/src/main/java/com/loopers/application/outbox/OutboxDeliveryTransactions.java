package com.loopers.application.outbox;

import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Each call ends its short Outbox-only transaction before the relay can perform network I/O. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class OutboxDeliveryTransactions {
    private final OutboxRepository repository;
    private final OutboxRetryPolicy retries;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public List<Long> findDueIds(Instant now, int limit) {
        return repository.findDueIds(now, limit);
    }

    public Optional<OutboxDeliveryAttempt> beginAttempt(long id, Instant now, Duration sendBudget) {
        if (now == null || sendBudget == null || sendBudget.isZero() || sendBudget.isNegative()) {
            throw new IllegalArgumentException("Outbox attempt requires a start time and positive send budget");
        }
        return repository.findByIdForUpdate(id).filter(message -> message.isDue(now)).map(message -> {
            int nextCount = Math.addExact(message.getAttemptCount(), 1);
            Instant recoveryAt = now.plus(sendBudget).plus(retries.delayFor(nextCount));
            int count = message.beginAttempt(now, recoveryAt);
            return new OutboxDeliveryAttempt(message.getId(), message.getEventId(), message.getTopic(),
                message.getMessageKey(), message.getPayload(), count);
        });
    }

    public boolean markPublished(long id, int attempt, Instant at) {
        return repository.findByIdForUpdate(id).map(message -> message.markPublished(attempt, at)).orElse(false);
    }

    public boolean markFailed(long id, int attempt, Instant at, String safeErrorCode) {
        if (at == null) {
            throw new IllegalArgumentException("Outbox failure observation time is required");
        }
        return repository.findByIdForUpdate(id)
            .map(message -> message.markFailed(attempt, at.plus(retries.delayFor(attempt)), safeErrorCode)).orElse(false);
    }
}
