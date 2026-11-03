package com.loopers.config.kafka;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 과제 p.5 "실제 listener 설정" 표를 확인한다. yml 표기가 아니라 Kafka 가 읽는 ConsumerConfig 의 실효값과
 * 실제로 떠 있는 컨테이너의 설정을 본다.
 */
@SpringBootTest
class OrderActivityKafkaConfigTest {

    private final ConcurrentKafkaListenerContainerFactory<String, byte[]> factory;
    private final KafkaListenerEndpointRegistry registry;

    @Autowired
    OrderActivityKafkaConfigTest(
        @Qualifier(OrderActivityKafkaConfig.ORDER_ACTIVITY_LISTENER) ConcurrentKafkaListenerContainerFactory<String, byte[]> factory,
        KafkaListenerEndpointRegistry registry
    ) {
        this.factory = factory;
        this.registry = registry;
    }

    @DisplayName("Kafka 가 읽는 실효값: enable.auto.commit=false, auto.offset.reset=earliest, String key·byte[] value, 테스트용 작은 fetch 값.")
    @Test
    void effectiveConsumerConfig() {
        ConsumerConfig config = new ConsumerConfig(factory.getConsumerFactory().getConfigurationProperties());

        assertThat(config.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
        assertThat(config.getString(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(config.getClass(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(StringDeserializer.class);
        assertThat(config.getClass(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ByteArrayDeserializer.class);
        assertThat(config.getInt(ConsumerConfig.FETCH_MIN_BYTES_CONFIG)).isEqualTo(1);
        assertThat(config.getInt(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG)).isEqualTo(100);
        assertThat(config.getList(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG))
            .containsExactly(System.getProperty("spring.kafka.bootstrap-servers"));
    }

    @DisplayName("실제 컨테이너: purchase-activity group, record listener, concurrency 1, MANUAL_IMMEDIATE, 중단형 error handler.")
    @Test
    void runningContainerSettings() {
        MessageListenerContainer container = registry.getListenerContainers().stream()
            .filter(c -> "purchase-activity".equals(c.getGroupId()))
            .findFirst()
            .orElseThrow();

        assertThat(container.isRunning()).isTrue();
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(container.getContainerProperties().getTopics()).containsExactly("order-events");
        assertThat(container).isInstanceOf(ConcurrentMessageListenerContainer.class);
        assertThat(((ConcurrentMessageListenerContainer<?, ?>) container).getConcurrency()).isEqualTo(1);
        assertThat(((ConcurrentMessageListenerContainer<?, ?>) container).getCommonErrorHandler())
            .isInstanceOf(CommonContainerStoppingErrorHandler.class);
        assertThat(factory.isBatchListener()).isFalse();
    }
}
