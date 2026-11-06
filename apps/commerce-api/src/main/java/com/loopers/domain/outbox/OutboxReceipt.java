package com.loopers.domain.outbox;

/** A successful producer result. It does not imply consumer processing or a saved PUBLISHED state. */
public record OutboxReceipt(String topic, int partition, long offset) {
    public OutboxReceipt {
        if (topic == null || topic.isBlank() || partition < 0 || offset < 0) {
            throw new IllegalArgumentException("Broker receipt requires a topic, partition and offset");
        }
    }
}
