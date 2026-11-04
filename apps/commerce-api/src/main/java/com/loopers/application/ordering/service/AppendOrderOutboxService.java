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
        outbox.insert(new OrderOutboxEntry(event.eventId(), event.orderId(), OrderConfirmedEvent.TYPE,
            OrderConfirmedEvent.VERSION, topic, Long.toString(event.orderId()), encoder.encodePayload(event), event.confirmedAt()));
    }
}
