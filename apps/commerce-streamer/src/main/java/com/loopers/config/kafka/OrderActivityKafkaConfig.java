package com.loopers.config.kafka;

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
 * 확정 주문 이벤트(구매 활동) 전용 record listener factory. 기존 batch factory({@code BATCH_LISTENER})는 바꾸지 않는다.
 *
 * <ul>
 *   <li>record 단위: 한 건의 DB 트랜잭션 경계와 ack 위치가 한 record에 대응한다.</li>
 *   <li>{@code MANUAL_IMMEDIATE}: listener가 DB commit을 끝낸 뒤 {@code ack.acknowledge()}를 부르는 순간 그 offset을 commit한다.</li>
 *   <li>{@code enable.auto.commit=false}: Kafka가 읽는 실제 ConsumerConfig 값으로 고정한다. 자동 commit은 DB 완료와 무관하게 offset을 올린다.</li>
 *   <li>{@link CommonContainerStoppingErrorHandler}: record가 실패하면 그 record를 건너뛰지 않고 컨테이너를 멈춘다. 실패 record의 offset은 commit되지 않고, 원인을 고친 뒤 같은 group으로 다시 시작하면 같은 record가 다시 전달된다.</li>
 *   <li>{@code concurrency=1}: 한 listener 스레드가 record를 순서대로 처리한다.</li>
 *   <li>{@code auto.offset.reset=earliest}: 새 group이 이미 쌓인 확정 이벤트를 건너뛰지 않게 한다 (공통 설정의 latest는 새 group의 과거 이벤트를 버린다).</li>
 *   <li>fetch 대기를 짧게 둔다: 공통 batch factory의 {@code fetch.min.bytes=1MiB·fetch.max.wait.ms=5s}는 적은 양의 record를 최대 5초 늦게 보이게 한다. 확정 이벤트는 한 건씩 오므로 {@code 1 byte·500ms}로 둔다.</li>
 * </ul>
 */
@Configuration
public class OrderActivityKafkaConfig {

    public static final String FACTORY = "orderActivityKafkaListenerContainerFactory";

    static final int FETCH_MIN_BYTES = 1;
    static final int FETCH_MAX_WAIT_MS = 500;

    @Bean(name = FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> orderActivityKafkaListenerContainerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> consumerConfig = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerConfig.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, FETCH_MIN_BYTES);
        consumerConfig.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, FETCH_MAX_WAIT_MS);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerConfig));
        factory.setBatchListener(false);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
