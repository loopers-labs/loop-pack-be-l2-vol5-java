package com.loopers.application.order;

import com.loopers.kafka.event.OrderConfirmed;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RecordOrderConfirmedOutbox {
    private final OrderConfirmedOutbox outbox;

    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(OrderConfirmed event) {
        outbox.record(event);
    }
}
