package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderEventOutbox;
import com.loopers.domain.order.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderEventRecovery {

    public enum Result {
        APPENDED, ALREADY_PRESENT
    }

    private final OrderService orderService;
    private final OrderEventOutbox outbox;

    @Transactional
    public Result recover(Long orderId) {
        Order order = orderService.getForUpdate(orderId);
        return outbox.appendIfAbsent(order.confirmedEvent()) ? Result.APPENDED : Result.ALREADY_PRESENT;
    }
}
