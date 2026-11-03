package com.loopers.config.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 구매 활동 consumer 전용 record listener factory (설계 D13·D14). 기존 BATCH_LISTENER_DEFAULT 는 건드리지 않는다.
 * - record 단위, concurrency 1, MANUAL_IMMEDIATE: DB TX commit 뒤 consumer 스레드에서 ack
 * - enable.auto.commit=false 를 코드에서 명시해 yml 표기와 무관하게 실효값을 고정
 * - auto.offset.reset=earliest: 이 group 은 topic 에 이미 있는 확정 이벤트도 빠짐없이 읽는다 (재처리는 장부로 no-op)
 * - CommonContainerStoppingErrorHandler: 리스너가 예외를 던지면 record 를 건너뛰지 않고 컨테이너를 중단한다
 */
@Configuration
public class OrderActivityKafkaConfig {
    public static final String ORDER_ACTIVITY_LISTENER = "orderActivityKafkaListenerContainerFactory";
    public static final String DEAD_LETTER_KAFKA_TEMPLATE = "deadLetterKafkaTemplate";

    /**
     * DLT 전용 producer (설계 D12). key String, value 는 원본 byte[] 그대로. acks=all 은 kafka.yml 의 producer 설정에서 온다.
     * send() 가 metadata 를 못 받으면 max.block.ms(기본 60초) 동안 막히므로 발행 대기 상한과 같게 맞춘다.
     */
    @Bean
    public ProducerFactory<String, byte[]> deadLetterProducerFactory(
        KafkaProperties kafkaProperties,
        @Value("${purchase-activity.dead-letter.send-timeout-ms}") long sendTimeoutMs
    ) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, sendTimeoutMs);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean(name = DEAD_LETTER_KAFKA_TEMPLATE)
    public KafkaTemplate<String, byte[]> deadLetterKafkaTemplate(ProducerFactory<String, byte[]> deadLetterProducerFactory) {
        return new KafkaTemplate<>(deadLetterProducerFactory);
    }

    @Bean(name = ORDER_ACTIVITY_LISTENER)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> orderActivityKafkaListenerContainerFactory(
        KafkaProperties kafkaProperties,
        @Value("${purchase-activity.fetch-min-bytes}") int fetchMinBytes,
        @Value("${purchase-activity.fetch-max-wait-ms}") int fetchMaxWaitMs
    ) {
        Map<String, Object> consumerConfig = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerConfig.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, fetchMinBytes);
        consumerConfig.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, fetchMaxWaitMs);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerConfig));
        factory.setBatchListener(false);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
