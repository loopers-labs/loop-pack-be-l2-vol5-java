package com.loopers.interfaces.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 구매 활동 consumer 전용 record factory(docs/week6/design.md 7번). 공용 modules/kafka의 BATCH_LISTENER는 그대로 둔다.
 *
 * - record 단위, concurrency 1, MANUAL_IMMEDIATE(과제, docs/week6/decisions.md D-11). ack는 처리한 기록의 offset+1을 바로 commit한다.
 * - error handler는 CommonContainerStoppingErrorHandler다. listener가 던지는 건 "다시 하면 바뀌는" 실패와 분류하지 못한 실패뿐이라
 *   (D-8, D-29), 멈추고 사람이 재시작한다(D-20). 공용 자원(DB) 실패라 group 전체가 멈추는 게 실제 상황과 맞다.
 * - key는 String, value는 바이트 그대로 받는다. 파싱은 listener 쪽에서 한다(D-14). 공용 kafka.yml의 consumer `value-serializer`는
 *   오타라 무시되므로 기대지 않고 여기서 지정한다.
 * - 공용 설정(접속 정보 등)은 spring.kafka.*에서 가져오되, 동작을 정하는 값은 여기서 명시한다(D-15): enable.auto.commit=false,
 *   auto.offset.reset=earliest(D-12), fetch.min.bytes=1, fetch.max.wait.ms 작게. 공용 batch의 fetch 값(1MiB·5초)은 한 건씩
 *   처리하는 consumer에 지연만 준다.
 */
@Configuration
public class OrderConfirmedListenerConfig {

    public static final String FACTORY = "orderConfirmedRecordListenerFactory";
    public static final int FETCH_MAX_WAIT_MS = 200;

    @Bean(name = FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> orderConfirmedRecordListenerFactory(
        KafkaProperties kafkaProperties,
        ObjectProvider<SslBundles> sslBundles
    ) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(sslBundles.getIfAvailable()));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, FETCH_MAX_WAIT_MS);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setBatchListener(false);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
