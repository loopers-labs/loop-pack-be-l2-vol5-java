package com.loopers.infrastructure.persistence.ordering;

import com.loopers.application.ordering.event.OrderOutboxEntry;
import com.loopers.application.ordering.gateway.OrderEventTransport.Published;
import com.loopers.application.ordering.gateway.OrderOutboxRepository;
import com.loopers.infrastructure.persistence.ordering.entity.OrderOutboxJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class OrderOutboxJpaRepository implements OrderOutboxRepository {
    private final EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(OrderOutboxEntry entry) {
        entityManager.persist(new OrderOutboxJpaEntity(entry));
        entityManager.flush();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OrderOutboxEntry> findRecoveryCandidates(UUID eventId, long orderId, String eventType) {
        return entityManager.createQuery("""
                SELECT e FROM OrderOutboxJpaEntity e
                WHERE e.eventId = :eventId OR (e.orderId = :orderId AND e.eventType = :eventType)
                """, OrderOutboxJpaEntity.class)
            .setParameter("eventId", eventId.toString()).setParameter("orderId", orderId).setParameter("eventType", eventType)
            .setMaxResults(2).getResultList().stream().map(OrderOutboxJpaEntity::entry).toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Candidate> due(Instant now, int limit) {
        return entityManager.createQuery("""
                SELECT e FROM OrderOutboxJpaEntity e
                WHERE e.status = 'PENDING' AND e.nextAttemptAt <= :now
                ORDER BY e.nextAttemptAt, e.eventId
                """, OrderOutboxJpaEntity.class)
            .setParameter("now", now).setMaxResults(limit).getResultList().stream()
            .map(OrderOutboxJpaEntity::candidate).toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(Candidate candidate, Instant now, Instant nextAttemptAt) {
        return current(candidate, """
            UPDATE OrderOutboxJpaEntity
            SET attempts = attempts + 1, nextAttemptAt = :next
            WHERE eventId = :id AND status = 'PENDING' AND attempts = :attempts AND nextAttemptAt <= :now
            """, candidate.attempts()).setParameter("now", now).setParameter("next", nextAttemptAt).executeUpdate() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publish(Candidate candidate, Instant now, Published delivery) {
        return current(candidate, """
            UPDATE OrderOutboxJpaEntity
            SET status = 'PUBLISHED', publishedAt = :now, publishedTopic = :topic,
                publishedPartition = :partition, publishedOffset = :offset, lastError = null
            WHERE eventId = :id AND status = 'PENDING' AND attempts = :attempts
            """, candidate.attempts() + 1)
            .setParameter("now", now).setParameter("topic", delivery.topic())
            .setParameter("partition", delivery.partition()).setParameter("offset", delivery.offset()).executeUpdate() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(Candidate candidate, Instant nextAttemptAt, String reason, boolean exhausted) {
        return current(candidate, """
            UPDATE OrderOutboxJpaEntity SET nextAttemptAt = :next, lastError = :reason, status = :status
            WHERE eventId = :id AND status = 'PENDING' AND attempts = :attempts
            """, candidate.attempts() + 1)
            .setParameter("next", nextAttemptAt).setParameter("reason", reason)
            .setParameter("status", exhausted ? "BLOCKED" : "PENDING").executeUpdate() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void blockExhausted(Instant now, int maxAttempts) {
        entityManager.createQuery("""
            UPDATE OrderOutboxJpaEntity SET status = 'BLOCKED', lastError = 'ATTEMPT_OUTCOME_UNKNOWN'
            WHERE status = 'PENDING' AND attempts >= :maxAttempts AND nextAttemptAt <= :now
            """).setParameter("now", now).setParameter("maxAttempts", maxAttempts).executeUpdate();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean resume(UUID eventId, Instant now) {
        return entityManager.createQuery("""
            UPDATE OrderOutboxJpaEntity SET status = 'PENDING', attempts = 0, nextAttemptAt = :now
            WHERE eventId = :id AND status = 'BLOCKED'
            """).setParameter("id", eventId.toString()).setParameter("now", now).executeUpdate() == 1;
    }

    private Query current(Candidate candidate, String statement, int attempts) {
        return entityManager.createQuery(statement)
            .setParameter("id", candidate.eventId().toString()).setParameter("attempts", attempts);
    }
}
