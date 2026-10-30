package com.loopers.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 구매 활동 전용 consumer·record listener factory(소비 설계 6절). 공통 batch factory·consumerFactory 는 바꾸지 않고 이름 붙인 bean 을 따로 둔다.
 * 공통 consumer map 을 복사한 뒤 Kafka 가 실제로 읽는 ConsumerConfig 키로 덮어쓴다. YAML 의 value-serializer 오기·
 * enable-auto-commit 표기에 기대지 않는다.
 */
@Configuration
@EnableConfigurationProperties(OrderActivityKafkaProperties.class)
public class OrderActivityKafkaConfig {

    public static final String CONSUMER_FACTORY = "orderActivityConsumerFactory";
    public static final String LISTENER_CONTAINER_FACTORY = "orderActivityKafkaListenerContainerFactory";
    public static final String LISTENER_ID = "order-activity-listener";

    @Bean(CONSUMER_FACTORY)
    DefaultKafkaConsumerFactory<String, byte[]> orderActivityConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // 구독으로 topic 을 만들지 않는다. topic 은 명시적으로 준비한다.
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        // 유효 committed offset 이 없는 새 group 은 보존된 가장 오래된 record 부터 반영한다(사용자 선택).
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // 업무 단위·실패 record·ack 경계를 한 record 로 맞춘 초기값. 처리량 적정값이 아니다.
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * record 단위 listener. DB TX 가 끝난 뒤 listener 스레드에서 ack 하면 즉시 동기 commit 한다.
     * 오류는 container 를 비정상 중단시키며 실패 record 를 recover·skip 하지 않는다(재시작하면 다시 받는다).
     */
    @Bean(LISTENER_CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, byte[]> orderActivityKafkaListenerContainerFactory(
        @Qualifier(CONSUMER_FACTORY) ConsumerFactory<String, byte[]> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(false);
        factory.setConcurrency(1);
        ContainerProperties container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        container.setSyncCommits(true);
        container.setAsyncAcks(false);
        // 배정 시 offset commit 을 업무 완료 근거와 섞지 않는다.
        container.setAssignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
