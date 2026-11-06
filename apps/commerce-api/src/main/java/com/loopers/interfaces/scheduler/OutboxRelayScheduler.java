package com.loopers.interfaces.scheduler;

import com.loopers.application.outbox.OutboxRelay;
import org.springframework.scheduling.annotation.Scheduled;

/** Registered only by the explicitly enabled relay configuration. No public HTTP execution endpoint. */
public class OutboxRelayScheduler {
    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "#{@outboxRelaySettings.fixedDelay().toMillis()}", scheduler = "outboxRelayTaskScheduler")
    public void poll() {
        relay.runOnce();
    }
}
