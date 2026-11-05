package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutbox;
import com.loopers.domain.order.OrderOutboxRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class JsonOrderOutboxRecorder implements OrderOutboxRecorder {
    private final OrderOutboxJpaRepository outbox;
    private final ObjectMapper mapper;

    @Override
    public void record(OrderConfirmed event) {
        save(event, OrderConfirmedMessage.from(event));
    }

    @Override
    public boolean recordIfAbsent(OrderConfirmed event) {
        OrderConfirmedMessage message = OrderConfirmedMessage.from(event);
        Optional<OrderOutbox> existing = outbox.findByEventId(event.eventId());
        if (existing.isEmpty()) {
            save(event, message);
            return true;
        }
        if (!existing.get().getPayload().equals(toJson(message))) {
            throw new IllegalStateException("저장된 Outbox와 주문 확정 사실이 다릅니다: eventId=" + event.eventId());
        }
        return false;
    }

    private void save(OrderConfirmed event, OrderConfirmedMessage message) {
        outbox.saveAndFlush(OrderOutbox.pending(event, message.eventType(), message.schemaVersion(), toJson(message)));
    }

    private String toJson(OrderConfirmedMessage message) {
        try {
            return mapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("주문 확정 이벤트를 직렬화할 수 없습니다: eventId=" + message.eventId(), exception);
        }
    }
}
