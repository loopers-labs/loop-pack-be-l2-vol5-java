package com.loopers.application.ordering.gateway;

import java.util.concurrent.CompletableFuture;

public interface OrderEventTransport {
    CompletableFuture<Published> send(String key, byte[] payload);

    record Published(String topic, int partition, long offset) {}
}
