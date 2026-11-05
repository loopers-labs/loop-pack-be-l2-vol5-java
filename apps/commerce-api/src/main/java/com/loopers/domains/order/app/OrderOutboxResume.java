package com.loopers.domains.order.app;

import com.loopers.domains.order.app.port.OrderConfirmedOutbox;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderOutboxResume {
    public enum Result { RESUMED, NOT_BLOCKED }

    private final OrderConfirmedOutbox orderConfirmedOutbox;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OrderOutboxResume(OrderConfirmedOutbox orderConfirmedOutbox, TransactionTemplate transactionTemplate, Clock clock) {
        this.orderConfirmedOutbox = orderConfirmedOutbox;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    // 없는 eventId와 BLOCKED가 아닌 행은 구분하지 않고 NOT_BLOCKED다. 둘 다 "바꾼 것이 없다"는 같은 결과라서다.
    public Result resume(String eventId) {
        Integer resumed = transactionTemplate.execute(status -> orderConfirmedOutbox.resumeBlocked(eventId, clock.instant()));
        if (resumed != null && resumed == 1) {
            return Result.RESUMED;
        }
        return Result.NOT_BLOCKED;
    }
}
