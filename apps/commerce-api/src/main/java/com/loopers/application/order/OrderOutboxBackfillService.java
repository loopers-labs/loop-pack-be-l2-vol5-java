package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.outbox.OutboxMessageMatcher;
import com.loopers.domain.outbox.OutboxMessageSerializer;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.payment.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit internal recovery of one saved PG confirmation; never confirms, charges, reserves or publishes. */
@Service
@RequiredArgsConstructor
public class OrderOutboxBackfillService {
    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final OutboxMessageSerializer serializer;
    private final OutboxMessageMatcher matcher;
    private final OutboxRepository outbox;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result backfill(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Backfill requires a positive order identity");
        }
        // Keep the existing order -> payment attempt lock order; no product or stock locks are needed.
        var order = orders.lockById(orderId)
            .orElseThrow(() -> new IllegalStateException("Backfill source order does not exist"));
        var event = OrderConfirmedEvent.from(order);
        var attempt = attempts.lockByOrderId(orderId)
            .orElseThrow(() -> new IllegalStateException("Backfill source payment evidence is missing"));
        OrderPaymentInfo.from(order, attempt); // Existing saved order/payment consistency rules, no provider lookup.
        if (attempt.getTransactionKey() == null || attempt.getTransactionKey().isBlank()) {
            throw new IllegalStateException("Backfill source transaction identity is missing");
        }
        var candidate = serializer.serialize(event);
        var existing = outbox.findByEventId(event.eventId());
        if (existing.isPresent()) {
            var saved = existing.get();
            if (!matcher.matches(candidate, saved)) {
                throw new IllegalStateException("Stored Outbox conflicts with the confirmed order fact or destination");
            }
            return new Result(event.eventId(), saved.getId(), Outcome.ALREADY_PRESENT);
        }
        var saved = outbox.save(candidate);
        return new Result(event.eventId(), saved.getId(), Outcome.CREATED);
    }

    public enum Outcome { CREATED, ALREADY_PRESENT }

    public record Result(String eventId, long outboxId, Outcome outcome) { }
}
