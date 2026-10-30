package com.loopers.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class PurchaseActivityListenerConfig {

    public static final String FACTORY = "purchaseActivityListenerContainerFactory";
    public static final String LISTENER_ID = "purchase-activity";

    @Bean(name = FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> purchaseActivityListenerContainerFactory(
        KafkaProperties kafkaProperties
    ) {
        Map<String, Object> consumer = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer));
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
