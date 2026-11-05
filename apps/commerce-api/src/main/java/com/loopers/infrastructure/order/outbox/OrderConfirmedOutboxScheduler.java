package com.loopers.infrastructure.order.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@ConditionalOnProperty(prefix = "order.confirmed-event.relay", name = "enabled", havingValue = "true")
public class OrderConfirmedOutboxScheduler {

    private final OrderConfirmedOutboxRelay relay;

    @Scheduled(fixedDelayString = "${order.confirmed-event.relay.interval}",
        initialDelayString = "${order.confirmed-event.relay.interval}")
    public void poll() {
        relay.runDueBatch();
    }
}
