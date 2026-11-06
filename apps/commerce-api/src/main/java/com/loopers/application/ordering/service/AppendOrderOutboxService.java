package com.loopers.application.ordering.service;

import com.loopers.application.ordering.event.OrderConfirmedEvent;
import com.loopers.application.ordering.event.OrderOutboxEntry;
import com.loopers.application.ordering.gateway.OrderEventEncoder;
import com.loopers.application.ordering.gateway.OrderOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppendOrderOutboxService {
    private final OrderEventEncoder encoder;
    private final OrderOutboxRepository outbox;

    @Value("${order-events.topic:order-events}")
    private String topic;

    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(OrderConfirmedEvent event) {
        outbox.insert(entry(event));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertOrVerify(OrderConfirmedEvent event) {
        var expected = entry(event);
        var existing = outbox.findRecoveryCandidates(event.eventId(), event.orderId(), OrderConfirmedEvent.TYPE);
        if (existing.isEmpty()) {
            outbox.insert(expected);
            return true;
        }
        var stored = existing.getFirst();
        if (existing.size() != 1 || !expected.sameEnvelope(stored) || !encoder.samePayload(expected.payload(), stored.payload())) {
            throw new IllegalStateException("기존 Outbox와 확정 사실이 일치하지 않습니다. eventId=" + event.eventId());
        }
        return false;
    }

    private OrderOutboxEntry entry(OrderConfirmedEvent event) {
        return new OrderOutboxEntry(event.eventId(), event.orderId(), OrderConfirmedEvent.TYPE,
            OrderConfirmedEvent.VERSION, topic, Long.toString(event.orderId()), encoder.encodePayload(event), event.confirmedAt());
    }
}
