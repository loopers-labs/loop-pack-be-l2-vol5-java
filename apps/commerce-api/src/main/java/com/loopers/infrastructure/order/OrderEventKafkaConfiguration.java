package com.loopers.infrastructure.order;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Duration;
import java.util.HashMap;

@Configuration(proxyBeanMethods = false)
public class OrderEventKafkaConfiguration {
    @Bean
    public NewTopic orderEventsTopic(
            @Value("${order-events.topic:order-events}") String topic,
            @Value("${order-events.partitions:2}") int partitions,
            @Value("${order-events.replication-factor:1}") short replicationFactor) {
        return new NewTopic(topic, partitions, replicationFactor);
    }

    @Bean
    public ProducerFactory<String, byte[]> outboxProducerFactory(
            KafkaProperties properties, @Value("${order-events.publish-timeout:5s}") Duration publishTimeout) {
        var config = new HashMap<>(properties.buildProducerProperties());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, publishTimeout.toMillis());
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new ByteArraySerializer());
    }

    @Bean
    public KafkaTemplate<String, byte[]> outboxKafkaTemplate(
            @Qualifier("outboxProducerFactory") ProducerFactory<String, byte[]> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
