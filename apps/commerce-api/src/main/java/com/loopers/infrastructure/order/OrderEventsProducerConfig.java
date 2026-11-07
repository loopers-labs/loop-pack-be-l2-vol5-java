package com.loopers.infrastructure.order;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Outbox relay 전용 producer. 공용 kafka.yml producer 설정(acks=all·idempotence·delivery.timeout.ms 등)을 그대로 쓰고 value 직렬화만 String 으로 바꾼다.
 * Outbox payload 는 이미 JSON 문자열이라, 공용 JsonSerializer 로 보내면 문자열이 한 번 더 따옴표로 감싸진다.
 */
@Configuration
public class OrderEventsProducerConfig {

    @Bean
    public ProducerFactory<String, String> orderEventProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> orderEventKafkaTemplate(ProducerFactory<String, String> orderEventProducerFactory) {
        return new KafkaTemplate<>(orderEventProducerFactory);
    }
}
