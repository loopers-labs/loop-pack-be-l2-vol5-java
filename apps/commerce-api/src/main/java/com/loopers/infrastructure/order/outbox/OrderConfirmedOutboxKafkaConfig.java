package com.loopers.infrastructure.order.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class OrderConfirmedOutboxKafkaConfig {

    @Bean(name = "orderConfirmedOutboxKafkaTemplate")
    public KafkaTemplate<Object, Object> orderConfirmedOutboxKafkaTemplate(
        ProducerFactory<Object, Object> sharedProducerFactory,
        @Value("${order.confirmed-event.relay.request-timeout}") Duration requestTimeout,
        @Value("${order.confirmed-event.relay.delivery-timeout}") Duration deliveryTimeout,
        @Value("${order.confirmed-event.relay.max-block-time}") Duration maxBlockTime
    ) {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        overrides.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        overrides.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.toIntExact(requestTimeout.toMillis()));
        overrides.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Math.toIntExact(deliveryTimeout.toMillis()));
        overrides.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Math.toIntExact(maxBlockTime.toMillis()));
        return new KafkaTemplate<>(sharedProducerFactory.copyWithConfigurationOverride(overrides));
    }
}
