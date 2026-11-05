package com.loopers.application.order;

import com.loopers.application.order.port.OrderConfirmedPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// commit된 확정만 보낸다. rollback이면 실행되지 않고, 트랜잭션 밖에서 발행한 이벤트는 조용히 건너뛴다(fallbackExecution 끔).
// commit 뒤라 DB에 쓰려면 REQUIRES_NEW가 필요하지만 지금은 쓰지 않는다.
@Component
public class OrderConfirmedEventListener {
    private final OrderConfirmedPublisher orderConfirmedPublisher;

    public OrderConfirmedEventListener(OrderConfirmedPublisher orderConfirmedPublisher) {
        this.orderConfirmedPublisher = orderConfirmedPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfirmed(OrderConfirmedPayload payload) {
        orderConfirmedPublisher.send(payload);
    }
}
