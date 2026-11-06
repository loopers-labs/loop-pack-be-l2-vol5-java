package com.loopers.infrastructure.order.outbox;

import com.loopers.application.order.event.OrderConfirmedMessageV1;
import com.loopers.application.order.event.OrderConfirmedOutboxStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxStoreAdapter implements OrderConfirmedOutboxStore {

    private final OrderConfirmedOutboxJpaRepository outboxJpaRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void savePending(OrderConfirmedMessageV1 message, String payload, ZonedDateTime nextAttemptAt) {
        outboxJpaRepository.saveAndFlush(OrderConfirmedOutboxJpaEntity.create(message, payload, nextAttemptAt));
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Optional<StoredMessage> findByEventId(String eventId) {
        return outboxJpaRepository.findByEventId(eventId).map(row -> new StoredMessage(
            row.getEventId(), row.getOrderId(), row.getEventType(), row.getSchemaVersion(),
            row.getMessageKey(), row.getPayload()));
    }
}
