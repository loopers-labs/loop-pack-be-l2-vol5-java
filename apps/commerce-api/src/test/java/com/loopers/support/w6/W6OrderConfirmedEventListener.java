package com.loopers.support.w6;

import com.loopers.application.order.OrderConfirmedEvent;
import com.loopers.application.order.OrderConfirmedPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** W6 의 after-commit 직접 발행 (revision 6f7aac1 의 OrderConfirmedEventListener 그대로). 발행 실패는 삼키고 로그만 남긴다. */
public class W6OrderConfirmedEventListener {
    private static final Logger log = LoggerFactory.getLogger(W6OrderConfirmedEventListener.class);

    private final OrderConfirmedPublisher orderConfirmedPublisher;

    W6OrderConfirmedEventListener(OrderConfirmedPublisher orderConfirmedPublisher) {
        this.orderConfirmedPublisher = orderConfirmedPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(OrderConfirmedEvent event) {
        try {
            orderConfirmedPublisher.publish(event);
        } catch (RuntimeException e) {
            log.error("OrderConfirmed 발행 실패, 전송 전 [eventId = {}, orderId = {}]", event.eventId(), event.orderId(), e);
        }
    }
}
