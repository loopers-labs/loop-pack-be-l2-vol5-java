package com.loopers.interfaces.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;

@EnableKafka
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaProperties.class)
@ConditionalOnProperty(prefix = "commerce.order-events.consumer", name = "enabled", havingValue = "true")
public class OrderConfirmedKafkaConfiguration {
    public static final String FACTORY = "orderConfirmedRecordKafkaListenerContainerFactory";
    public static final String ERROR_HANDLER = "orderConfirmedKafkaErrorHandler";

    @Bean(name = FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> orderConfirmedFactory(
        KafkaProperties kafkaProperties,
        @Qualifier(ERROR_HANDLER) CommonErrorHandler errorHandler,
        @Value("${commerce.order-events.consumer.concurrency}") int concurrency
    ) {
        var settings = new HashMap<String, Object>(kafkaProperties.buildConsumerProperties());
        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        settings.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(settings));
        factory.setBatchListener(false);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setAssignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
