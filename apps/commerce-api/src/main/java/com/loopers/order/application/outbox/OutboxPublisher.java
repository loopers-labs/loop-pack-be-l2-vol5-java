package com.loopers.order.application.outbox;

import java.util.concurrent.CompletableFuture;

public interface OutboxPublisher {

    CompletableFuture<PublishResult> publish(String messageKey, String payload);
}
