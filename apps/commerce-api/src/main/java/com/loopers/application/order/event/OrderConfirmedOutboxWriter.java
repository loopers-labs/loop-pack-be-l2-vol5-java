package com.loopers.application.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.time.ApplicationTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxWriter {

    private final ObjectMapper objectMapper;
    private final OrderConfirmedOutboxStore outboxStore;
    private final ApplicationTime applicationTime;

    public void record(OrderConfirmedEvent event) {
        MessageSnapshot snapshot = snapshot(event);
        outboxStore.savePending(snapshot.message(), snapshot.payload(), snapshot.nextAttemptAt());
    }

    public boolean ensureRecorded(OrderConfirmedEvent event) {
        MessageSnapshot snapshot = snapshot(event);
        Optional<OrderConfirmedOutboxStore.StoredMessage> existing = outboxStore.findByEventId(
            snapshot.message().eventId());
        if (existing.isPresent()) {
            verifySameMessage(existing.get(), snapshot);
            return false;
        }
        outboxStore.savePending(snapshot.message(), snapshot.payload(), snapshot.nextAttemptAt());
        return true;
    }

    private MessageSnapshot snapshot(OrderConfirmedEvent event) {
        OrderConfirmedMessageV1 message = OrderConfirmedMessageV1.from(event);
        try {
            String payload = objectMapper.writeValueAsString(message);
            return new MessageSnapshot(message, payload, applicationTime.now());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("주문 확정 메시지를 Outbox에 직렬화하지 못했습니다.", failure);
        }
    }

    private void verifySameMessage(OrderConfirmedOutboxStore.StoredMessage existing, MessageSnapshot expected) {
        OrderConfirmedMessageV1 message = expected.message();
        if (!existing.eventId().equals(message.eventId())
            || !existing.orderId().equals(message.orderId())
            || !existing.eventType().equals(message.eventType())
            || existing.schemaVersion() != message.schemaVersion()
            || !existing.messageKey().equals(message.key())
            || !existing.payload().equals(expected.payload())) {
            throw new IllegalStateException("기존 Outbox 메시지가 저장된 주문 확정 사실과 일치하지 않습니다.");
        }
    }

    private record MessageSnapshot(OrderConfirmedMessageV1 message, String payload, ZonedDateTime nextAttemptAt) {}
}
