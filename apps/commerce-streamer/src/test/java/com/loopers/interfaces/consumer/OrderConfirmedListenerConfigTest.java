package com.loopers.interfaces.consumer;

import com.loopers.confg.kafka.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제로 만들어진 우리 consumer container의 설정 값을 확인한다(docs/week6/design.md 7번, docs/week6/decisions.md D-11, D-14, D-15).
 * 공용 kafka.yml·BATCH_LISTENER의 값(auto offset latest, 1MiB·5초 fetch, batch, concurrency 3)이 섞여 들어오지 않았는지가 핵심이다.
 */
@SpringBootTest
class OrderConfirmedListenerConfigTest {

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    @Qualifier(OrderConfirmedListenerConfig.FACTORY)
    private ConcurrentKafkaListenerContainerFactory<String, byte[]> factory;

    @Autowired
    @Qualifier(KafkaConfig.BATCH_LISTENER)
    private ConcurrentKafkaListenerContainerFactory<Object, Object> sharedBatchFactory;

    @DisplayName("구매 활동 container는 record 단위·concurrency 1·MANUAL_IMMEDIATE·멈춤 handler이고, consumer 설정은 auto commit 끔·"
        + "earliest·fetch.min.bytes 1·작은 fetch 대기·key String·value 바이트다.")
    @Test
    void containerAndConsumerConfig() {
        // arrange
        MessageListenerContainer container = registry.getListenerContainer(OrderConfirmedListener.LISTENER_ID);
        Map<String, Object> consumer = factory.getConsumerFactory().getConfigurationProperties();

        // assert
        assertThat(container).isInstanceOf(ConcurrentMessageListenerContainer.class);
        ConcurrentMessageListenerContainer<?, ?> concurrent = (ConcurrentMessageListenerContainer<?, ?>) container;
        assertAll(
            () -> assertThat(concurrent.getConcurrency()).isEqualTo(1),
            () -> assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE),
            () -> assertThat(concurrent.getCommonErrorHandler()).isInstanceOf(CommonContainerStoppingErrorHandler.class),
            () -> assertThat(factory.isBatchListener()).isFalse(),
            () -> assertThat(container.getGroupId()).isEqualTo("purchase-activity"),
            () -> assertThat(consumer.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false),
            () -> assertThat(consumer.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest"),
            () -> assertThat(consumer.get(ConsumerConfig.FETCH_MIN_BYTES_CONFIG)).isEqualTo(1),
            () -> assertThat(consumer.get(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG)).isEqualTo(OrderConfirmedListenerConfig.FETCH_MAX_WAIT_MS),
            () -> assertThat((Integer) consumer.get(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG)).isLessThanOrEqualTo(500),
            () -> assertThat(consumer.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(StringDeserializer.class),
            () -> assertThat(consumer.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ByteArrayDeserializer.class),
            // 공용 batch factory는 그대로다(D-15)
            () -> assertThat(sharedBatchFactory.isBatchListener()).isTrue()
        );
    }
}
