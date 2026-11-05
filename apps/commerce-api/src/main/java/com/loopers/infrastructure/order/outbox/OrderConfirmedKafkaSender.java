package com.loopers.infrastructure.order.outbox;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class OrderConfirmedKafkaSender {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    public OrderConfirmedKafkaSender(
        @Qualifier("orderConfirmedOutboxKafkaTemplate") KafkaTemplate<Object, Object> kafkaTemplate,
        @Value("${order.confirmed-event.topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public CompletableFuture<SendResult<Object, Object>> send(String key, String payload) {
        return kafkaTemplate.send(topic, key, payload);
    }
}
