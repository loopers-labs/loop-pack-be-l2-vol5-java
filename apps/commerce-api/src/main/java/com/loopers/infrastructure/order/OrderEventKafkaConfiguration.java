package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.loopers.kafka.event.OrderConfirmed;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

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
    public ProducerFactory<String, OrderConfirmed> orderConfirmedProducerFactory(
            KafkaProperties properties,
            ObjectMapper objectMapper,
            @Value("${order-events.publish-timeout:5s}") Duration publishTimeout) {
        var config = new HashMap<>(properties.buildProducerProperties());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, publishTimeout.toMillis());
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        var serializer = new JsonSerializer<OrderConfirmed>(
                objectMapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), serializer);
    }

    @Bean
    public KafkaTemplate<String, OrderConfirmed> orderConfirmedKafkaTemplate(
            @Qualifier("orderConfirmedProducerFactory") ProducerFactory<String, OrderConfirmed> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
