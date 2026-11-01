package com.loopers.interfaces.consumer;

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

import java.util.HashMap;
import java.util.Map;

/**
 * 구매 활동 소비용 record listener factory. 공용 배치 factory 는 건드리지 않는다.
 *
 * <p>DB 에 반영한 뒤에만 ack 하고(MANUAL_IMMEDIATE), 실패한 record 는 건너뛰지 않고 컨테이너를 멈춘다. 이 handler 는 멈춘
 * 뒤 항상 예외를 던지므로 실패 record 의 offset 이 commit 되지 않는다. 새 group 도 지나간 확정 주문을 놓치지 않게 처음부터
 * 읽는다.
 */
@Configuration
public class OrderActivityKafkaConfig {

    public static final String ORDER_ACTIVITY_LISTENER = "orderActivityKafkaListenerContainerFactory";

    @Bean(ORDER_ACTIVITY_LISTENER)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> orderActivityKafkaListenerContainerFactory(
        KafkaProperties kafkaProperties
    ) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new ByteArrayDeserializer()));
        factory.setBatchListener(false);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
