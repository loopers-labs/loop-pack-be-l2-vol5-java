package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxReceipt;
import com.loopers.domain.outbox.OutboxSender;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Sends already serialized facts. All delivery state and waiting deadlines belong to the relay. */
public class KafkaOutboxSender implements OutboxSender {
    private final KafkaTemplate<String, String> kafka;

    public KafkaOutboxSender(KafkaTemplate<String, String> kafka) {
        this.kafka = Objects.requireNonNull(kafka, "kafka");
    }

    @Override
    public CompletableFuture<OutboxReceipt> send(OutboxDeliveryAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        // No JSON serialization, route lookup, retry loop, get/join, or DB callback here.
        return kafka.send(attempt.topic(), attempt.messageKey(), attempt.payload()).thenApply(result -> {
            var metadata = result.getRecordMetadata();
            return new OutboxReceipt(metadata.topic(), metadata.partition(), metadata.offset());
        });
    }
}
