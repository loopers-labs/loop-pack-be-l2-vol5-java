package com.loopers.interfaces.scheduler;

import com.loopers.application.ordering.service.OrderOutboxRelayService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!outbox-resume & !outbox-recover")
@ConditionalOnProperty(name = "order-events.relay.enabled", havingValue = "true")
@RequiredArgsConstructor
public class OrderOutboxScheduler {
    private final OrderOutboxRelayService relay;

    @Scheduled(fixedDelay = 3_000, scheduler = "orderOutboxTaskScheduler")
    public void publishPendingOrders() {
        relay.poll();
    }
}
