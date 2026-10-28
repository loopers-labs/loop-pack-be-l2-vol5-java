package com.loopers.infrastructure.purchase;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(PurchaseActivityConsumerProperties.class)
public class PurchaseActivityKafkaConfig {
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> orderActivityKafkaListenerContainerFactory(
        KafkaProperties kafkaProperties,
        PurchaseActivityConsumerProperties consumerProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildConsumerProperties());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, consumerProperties.autoOffsetReset());
        properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, consumerProperties.allowAutoCreateTopics());
        properties.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, consumerProperties.fetchMinBytes());
        properties.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,
            Math.toIntExact(consumerProperties.fetchMaxWait().toMillis()));
        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties));
        factory.setBatchListener(false);
        factory.setConcurrency(consumerProperties.concurrency());
        factory.getContainerProperties().setAckMode(consumerProperties.ackMode());
        factory.getContainerProperties().setSyncCommits(consumerProperties.syncCommits());
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
