package com.loopers.application.order;

import com.loopers.kafka.event.OrderConfirmed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PublishOrderConfirmedAfterCommit {
    private final OrderConfirmedPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(OrderConfirmed event) {
        try {
            var sent = publisher.publish(event);
            log.info("order_confirmation_published eventId={} orderId={} topic={} partition={} offset={}",
                    event.eventId(), event.orderId(), sent.topic(), sent.partition(), sent.offset());
        } catch (RuntimeException exception) {
            log.error("order_confirmation_publication_unconfirmed eventId={} orderId={} confirmedAt={}",
                    event.eventId(), event.orderId(), event.confirmedAt(), exception);
        }
    }
}
