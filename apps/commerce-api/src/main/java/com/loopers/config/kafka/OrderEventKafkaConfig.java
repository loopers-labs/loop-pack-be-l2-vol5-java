package com.loopers.config.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 주문 확정 이벤트 전용 producer (6주차 설계 D6). key 는 String, value 는 우리가 만든 JSON byte[] 그대로 보낸다.
 * 공용 KafkaTemplate<Object, Object>(JsonSerializer) 는 건드리지 않는다. acks=all 은 kafka.yml 의 producer 설정에서 온다 (D8).
 */
@Configuration
public class OrderEventKafkaConfig {
    public static final String ORDER_EVENT_KAFKA_TEMPLATE = "orderEventKafkaTemplate";

    @Bean
    public ProducerFactory<String, byte[]> orderEventProducerFactory(
        KafkaProperties kafkaProperties,
        @Value("${order-events.send-timeout-ms}") long sendTimeoutMs
    ) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        // send() 는 metadata 를 못 받으면 max.block.ms(기본 60초) 동안 막힌다. 발행 대기 상한(D7)과 같게 맞춘다.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, sendTimeoutMs);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean(name = ORDER_EVENT_KAFKA_TEMPLATE)
    public KafkaTemplate<String, byte[]> orderEventKafkaTemplate(ProducerFactory<String, byte[]> orderEventProducerFactory) {
        return new KafkaTemplate<>(orderEventProducerFactory);
    }
}
