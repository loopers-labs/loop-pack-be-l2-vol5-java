package com.loopers.order.infrastructure.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OrderConfirmedOutboxScheduler {

    private final OrderConfirmedOutboxRelay relay;

    public OrderConfirmedOutboxScheduler(OrderConfirmedOutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(
        fixedDelayString = "#{@outboxRelayProperties.pollInterval.toMillis()}",
        timeUnit = TimeUnit.MILLISECONDS
    )
    public void poll() {
        relay.pollOnce();
    }
}
