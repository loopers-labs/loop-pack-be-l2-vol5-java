package com.loopers.infrastructure.messaging.ordering;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.loopers.application.ordering.event.OrderConfirmedEvent;
import com.loopers.application.ordering.gateway.OrderEventTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderConfirmedPublicationListener {
    private final OrderEventTransport transport;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(OrderConfirmedEvent event) {
        try {
            transport.send(Long.toString(event.orderId()), OrderConfirmedEventJson.encode(event))
                .whenComplete((published, failure) -> {
                    if (failure == null) {
                        log.info("OrderConfirmed sent eventId={} orderId={} topic={} partition={} offset={}",
                            event.eventId(), event.orderId(), published.topic(), published.partition(), published.offset());
                    } else {
                        failed(event, failure);
                    }
                });
        } catch (JsonProcessingException | RuntimeException failure) {
            failed(event, failure);
        }
    }

    private void failed(OrderConfirmedEvent event, Throwable failure) {
        log.error("OrderConfirmed send failed eventId={} orderId={} cause={}",
            event.eventId(), event.orderId(), failure.getClass().getSimpleName());
    }
}
