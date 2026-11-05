package com.loopers.application.order;

import com.loopers.domain.order.OrderOutbox;
import com.loopers.domain.order.OrderOutboxDelivery;
import com.loopers.domain.order.OrderOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** relay의 짧은 DB TX 구간. Kafka 응답을 기다리는 동안에는 어떤 TX도 열어 두지 않는다. */
@RequiredArgsConstructor
@Component
public class OrderOutboxTransactions {
    private final OrderOutboxRepository outbox;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Long> findDueIds(int limit) {
        return outbox.findDue(clock.instant(), limit).stream().map(OrderOutbox::getId).toList();
    }

    @Transactional
    public OrderOutboxDelivery startAttempt(Long id) {
        OrderOutbox row = get(id);
        row.startAttempt();
        return new OrderOutboxDelivery(row.getEventId(), row.getMessageKey(), row.getPayload());
    }

    @Transactional
    public void markPublished(Long id) {
        get(id).markPublished(now());
    }

    @Transactional
    public void scheduleRetry(Long id, String error, Duration firstDelay, Duration maxDelay) {
        get(id).scheduleRetry(now(), error, firstDelay, maxDelay);
    }

    private OrderOutbox get(Long id) {
        return outbox.findById(id).orElseThrow(() -> new IllegalStateException("Outbox 행이 없습니다: id=" + id));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
