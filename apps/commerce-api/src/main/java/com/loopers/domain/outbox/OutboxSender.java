package com.loopers.domain.outbox;

import java.util.concurrent.CompletableFuture;

/** Returns broker acknowledgement, not merely local submission. It never changes Outbox state. */
public interface OutboxSender {
    CompletableFuture<OutboxReceipt> send(OutboxDeliveryAttempt attempt);
}
