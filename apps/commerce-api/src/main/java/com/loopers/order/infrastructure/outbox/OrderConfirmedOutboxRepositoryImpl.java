package com.loopers.order.infrastructure.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
class OrderConfirmedOutboxRepositoryImpl implements OrderConfirmedOutboxRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<OrderConfirmedOutbox> findReadyForRelay(Instant now, int limit) {
        return entityManager.createQuery("""
                select outbox from OrderConfirmedOutbox outbox
                where outbox.status in :statuses
                  and outbox.nextAttemptAt <= :now
                order by outbox.createdAt asc, outbox.id asc
                """, OrderConfirmedOutbox.class)
            .setParameter("statuses", List.of(
                OrderConfirmedOutboxStatus.PENDING,
                OrderConfirmedOutboxStatus.FAILED
            ))
            .setParameter("now", now)
            .setMaxResults(limit)
            .getResultList();
    }
}
