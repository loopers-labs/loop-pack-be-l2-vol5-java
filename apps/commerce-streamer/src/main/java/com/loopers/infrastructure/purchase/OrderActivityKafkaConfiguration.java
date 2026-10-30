package com.loopers.infrastructure.purchase;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.Duration;
import java.util.HashMap;

/** Dedicated record boundary; the existing shared batch factory is unchanged. */
@Configuration(proxyBeanMethods = false)
public class OrderActivityKafkaConfiguration {
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> orderActivityKafkaListenerContainerFactory(
            KafkaProperties properties) {
        var config = new HashMap<String, Object>(properties.buildConsumerProperties());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setBatchListener(false);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setAsyncAcks(false);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setSyncCommitTimeout(Duration.ofSeconds(5));
        factory.getContainerProperties().setPollTimeout(100);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
