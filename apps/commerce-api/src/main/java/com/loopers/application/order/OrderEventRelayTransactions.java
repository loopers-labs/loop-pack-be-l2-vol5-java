package com.loopers.application.order;

import com.loopers.domain.order.OrderEventDelivery;
import com.loopers.domain.order.OrderEventOutbox;
import com.loopers.domain.order.PendingOrderEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderEventRelayTransactions {

    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(1);
    private static final int MAX_DOUBLINGS = 6;

    private final OrderEventOutbox outbox;

    @Transactional(readOnly = true)
    public List<PendingOrderEvent> due(int limit) {
        return outbox.due(limit);
    }

    @Transactional
    public void record(List<OrderEventDelivery> deliveries) {
        List<Long> published = new ArrayList<>();
        for (OrderEventDelivery delivery : deliveries) {
            switch (delivery) {
                case OrderEventDelivery.Published sent -> published.add(sent.event().id());
                case OrderEventDelivery.Rejected rejected ->
                    outbox.markBlocked(rejected.event().id(), rejected.error());
                case OrderEventDelivery.Unconfirmed unconfirmed ->
                    outbox.retryLater(unconfirmed.event().id(), backoff(unconfirmed.event().attemptCount()),
                        unconfirmed.error());
            }
        }
        outbox.markPublished(published);
    }

    static Duration backoff(int attemptCount) {
        Duration doubled = FIRST_BACKOFF.multipliedBy(1L << Math.min(attemptCount, MAX_DOUBLINGS));
        return doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
    }
}
