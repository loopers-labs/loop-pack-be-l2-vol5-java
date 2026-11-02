package com.loopers.purchaseactivity.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.Duration;
import java.util.HashMap;

@Configuration
@EnableKafka
public class PurchaseActivityKafkaConfiguration {
    public static final String FACTORY = "orderActivityKafkaListenerContainerFactory";

    @Bean(name = FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> orderActivityKafkaListenerContainerFactory(
            KafkaProperties properties,
            @Value("${purchase-activity.concurrency:1}") int concurrency,
            @Value("${purchase-activity.poll-timeout-ms:1000}") long pollTimeout,
            @Value("${purchase-activity.fetch-min-bytes:1}") int fetchMinBytes,
            @Value("${purchase-activity.fetch-max-wait-ms:500}") int fetchMaxWait) {
        var consumerProperties = new HashMap<>(properties.buildConsumerProperties());
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, fetchMinBytes);
        consumerProperties.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, fetchMaxWait);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerProperties));
        factory.setBatchListener(false);
        factory.setConcurrency(concurrency);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setSyncCommitTimeout(Duration.ofSeconds(10));
        factory.getContainerProperties().setPollTimeout(pollTimeout);
        return factory;
    }
}
