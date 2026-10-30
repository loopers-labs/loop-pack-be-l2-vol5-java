package com.loopers.purchase.infrastructure.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class OrderActivityKafkaConfiguration {

    public static final String LISTENER_CONTAINER_FACTORY = "orderActivityKafkaListenerContainerFactory";

    @Bean
    public ConsumerFactory<String, byte[]> orderActivityConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> consumerProperties = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(
            consumerProperties,
            new StringDeserializer(),
            new ByteArrayDeserializer()
        );
    }

    @Bean(name = LISTENER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> orderActivityKafkaListenerContainerFactory(
        ConsumerFactory<String, byte[]> orderActivityConsumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(orderActivityConsumerFactory);
        factory.setBatchListener(false);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
