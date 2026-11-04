package com.loopers.infrastructure.messaging.ordering;

import java.time.Duration;
import java.util.HashMap;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

@EnableKafka
@Configuration(proxyBeanMethods = false)
public class OrderConfirmedKafkaConfiguration {
    @Bean
    NewTopic orderEventsTopic(@Value("${order-events.topic:order-events}") String topic) {
        return TopicBuilder.name(topic).partitions(2).replicas(1).build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, byte[]> orderConfirmedListenerFactory(KafkaProperties properties) {
        var config = new HashMap<>(properties.buildConsumerProperties());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        var container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        container.setSyncCommits(true);
        container.setSyncCommitTimeout(Duration.ofSeconds(5));
        container.setAsyncAcks(false);
        return factory;
    }
}
