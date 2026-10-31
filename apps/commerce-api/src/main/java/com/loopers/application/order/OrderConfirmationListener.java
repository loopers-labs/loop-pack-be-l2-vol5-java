package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmationPublisher;
import com.loopers.domain.order.OrderConfirmed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "commerce.order-events.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class OrderConfirmationListener {
    private final OrderConfirmationPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfirmed(OrderConfirmed event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException exception) {
            // The order has already committed. Publishing failure must not retry payment or undo confirmation.
            log.error("Order event publication failed after commit: eventId={}, orderId={}",
                event.eventId(), event.orderId(), exception);
        }
    }
}
