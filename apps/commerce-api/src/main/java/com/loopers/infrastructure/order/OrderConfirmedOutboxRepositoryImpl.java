package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static com.loopers.domain.order.QOrderConfirmedOutbox.orderConfirmedOutbox;

@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxRepositoryImpl implements OrderConfirmedOutboxRepository {
    private final OrderConfirmedOutboxJpaRepository orderConfirmedOutboxJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public OrderConfirmedOutbox save(OrderConfirmedOutbox outbox) {
        return orderConfirmedOutboxJpaRepository.save(outbox);
    }

    @Override
    public Optional<OrderConfirmedOutbox> findByEventId(String eventId) {
        return orderConfirmedOutboxJpaRepository.findByEventId(eventId);
    }

    @Override
    public Optional<OrderConfirmedOutbox> findByOrderId(Long orderId) {
        return orderConfirmedOutboxJpaRepository.findByOrderId(orderId);
    }

    @Override
    public List<OrderConfirmedOutbox> findPendingDue(ZonedDateTime now, int limit) {
        return queryFactory
            .selectFrom(orderConfirmedOutbox)
            .where(
                orderConfirmedOutbox.status.eq(OrderConfirmedOutbox.Status.PENDING),
                orderConfirmedOutbox.nextAttemptAt.loe(now)
            )
            .orderBy(orderConfirmedOutbox.nextAttemptAt.asc(), orderConfirmedOutbox.id.asc())
            .limit(limit)
            .fetch();
    }

    @Override
    public int startAttempt(Long id) {
        return (int) queryFactory
            .update(orderConfirmedOutbox)
            .set(orderConfirmedOutbox.attemptCount, orderConfirmedOutbox.attemptCount.add(1))
            .set(orderConfirmedOutbox.updatedAt, ZonedDateTime.now())
            .where(orderConfirmedOutbox.id.eq(id), orderConfirmedOutbox.status.eq(OrderConfirmedOutbox.Status.PENDING))
            .execute();
    }

    @Override
    public int markPublished(Long id, ZonedDateTime publishedAt) {
        return (int) queryFactory
            .update(orderConfirmedOutbox)
            .set(orderConfirmedOutbox.status, OrderConfirmedOutbox.Status.PUBLISHED)
            .set(orderConfirmedOutbox.publishedAt, publishedAt)
            .set(orderConfirmedOutbox.updatedAt, ZonedDateTime.now())
            .where(orderConfirmedOutbox.id.eq(id), orderConfirmedOutbox.status.eq(OrderConfirmedOutbox.Status.PENDING))
            .execute();
    }

    @Override
    public int scheduleRetry(Long id, ZonedDateTime nextAttemptAt, String lastError) {
        return (int) queryFactory
            .update(orderConfirmedOutbox)
            .set(orderConfirmedOutbox.nextAttemptAt, nextAttemptAt)
            .set(orderConfirmedOutbox.lastError, lastError)
            .set(orderConfirmedOutbox.updatedAt, ZonedDateTime.now())
            .where(orderConfirmedOutbox.id.eq(id), orderConfirmedOutbox.status.eq(OrderConfirmedOutbox.Status.PENDING))
            .execute();
    }

    @Override
    public int markBlocked(Long id, String lastError) {
        return (int) queryFactory
            .update(orderConfirmedOutbox)
            .set(orderConfirmedOutbox.status, OrderConfirmedOutbox.Status.BLOCKED)
            .set(orderConfirmedOutbox.lastError, lastError)
            .set(orderConfirmedOutbox.updatedAt, ZonedDateTime.now())
            .where(orderConfirmedOutbox.id.eq(id), orderConfirmedOutbox.status.eq(OrderConfirmedOutbox.Status.PENDING))
            .execute();
    }

    @Override
    public int resumeBlocked(String eventId, ZonedDateTime now) {
        return (int) queryFactory
            .update(orderConfirmedOutbox)
            .set(orderConfirmedOutbox.status, OrderConfirmedOutbox.Status.PENDING)
            .set(orderConfirmedOutbox.nextAttemptAt, now)
            .set(orderConfirmedOutbox.updatedAt, ZonedDateTime.now())
            .where(orderConfirmedOutbox.eventId.eq(eventId), orderConfirmedOutbox.status.eq(OrderConfirmedOutbox.Status.BLOCKED))
            .execute();
    }

    @Override
    public long countPending() {
        Long count = queryFactory
            .select(orderConfirmedOutbox.count())
            .from(orderConfirmedOutbox)
            .where(orderConfirmedOutbox.status.eq(OrderConfirmedOutbox.Status.PENDING))
            .fetchOne();
        return count == null ? 0L : count;
    }
}
