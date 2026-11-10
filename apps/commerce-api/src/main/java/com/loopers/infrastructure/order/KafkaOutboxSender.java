package com.loopers.infrastructure.order;

import com.loopers.application.order.OutboxKafkaSender;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaOutboxSender implements OutboxKafkaSender {
    private final KafkaTemplate<String, byte[]> kafka;
    private final Duration timeout;

    public KafkaOutboxSender(@Qualifier("outboxKafkaTemplate") KafkaTemplate<String, byte[]> kafka,
            @Value("${order-events.publish-timeout:5s}") Duration timeout) {
        this.kafka = kafka;
        this.timeout = timeout;
    }

    @Override
    public void send(String topic, String key, String payload) {
        try {
            kafka.send(topic, key, payload.getBytes(StandardCharsets.UTF_8)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Outbox send interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Outbox send was not confirmed", exception);
        }
    }
}
