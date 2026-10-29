package com.loopers.domain.order;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class OrderExpiryScheduler {

    private final OrderService orderService;

    @Scheduled(fixedDelay = 60_000)
    public void expireOrders() {
        orderService.expireDraftOrders();
    }
}
