package com.loopers.config.kafka;

import com.loopers.interfaces.consumer.OrderConfirmedListener;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구매 활동 listener의 실제 설정을 확인한다 (W6 과제 4절). yml에 적힌 값이 아니라 Kafka가 읽는 실제 {@code ConsumerConfig} 값과
 * 컨테이너가 쓰는 설정을 본다. 시작 저장소의 {@code kafka.yml}에는 consumer가 {@code value-serializer}로 적히고
 * {@code enable-auto-commit}이 하이픈 표기로 적혀 있어, 표기만으로는 실제 값을 믿을 수 없었다.
 */
@SpringBootTest(properties = "order-event.listener-auto-startup=false")
class OrderActivityKafkaConfigTest {

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaBrokerFixture::bootstrapServers);
    }

    @Autowired
    @Qualifier(OrderActivityKafkaConfig.FACTORY)
    private ConcurrentKafkaListenerContainerFactory<String, byte[]> factory;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @DisplayName("Kafka가 읽는 consumer 설정은 자동 commit 끔, String key·byte[] value 역직렬화다.")
    @Test
    void consumerConfigIsWhatKafkaReads() {
        // act
        Map<String, Object> config = factory.getConsumerFactory().getConfigurationProperties();

        // assert
        assertThat(config.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        assertThat(config.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(StringDeserializer.class);
        assertThat(config.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ByteArrayDeserializer.class);
        assertThat(config.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(config.get(ConsumerConfig.FETCH_MIN_BYTES_CONFIG)).isEqualTo(1);
        assertThat(config.get(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG)).isEqualTo(500);
    }

    @DisplayName("공통 설정(kafka.yml)도 같은 값을 읽는다. 하이픈 표기의 enable-auto-commit이 아니라 실제 키로 false다.")
    @Test
    void sharedConfigAlsoDisablesAutoCommit() {
        // act
        Map<String, Object> consumerProperties = kafkaProperties.buildConsumerProperties();

        // assert
        assertThat(consumerProperties.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        assertThat(consumerProperties.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ByteArrayDeserializer.class);
    }

    @DisplayName("producer·consumer·admin은 모두 같은 실제 테스트 broker 주소를 쓴다.")
    @Test
    void allClientsUseTheSameBootstrapServers() {
        // act
        String expected = KafkaBrokerFixture.bootstrapServers();

        // assert
        assertThat(kafkaProperties.buildProducerProperties().get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo(List.of(expected));
        assertThat(kafkaProperties.buildConsumerProperties().get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo(List.of(expected));
        assertThat(kafkaProperties.buildAdminProperties(null).get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo(List.of(expected));
        assertThat(factory.getConsumerFactory().getConfigurationProperties().get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG))
            .isEqualTo(List.of(expected));
    }

    @DisplayName("컨테이너는 record 단위·MANUAL_IMMEDIATE·동시성 1이고, 실패하면 건너뛰지 않고 멈추는 error handler를 쓴다.")
    @Test
    void containerIsRecordManualImmediateAndStopsOnFailure() {
        // act
        ConcurrentMessageListenerContainer<String, byte[]> container = (ConcurrentMessageListenerContainer<String, byte[]>)
            factory.createContainer("order-events-config-check");

        // assert
        assertThat(factory.isBatchListener()).isFalse();
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(container.getConcurrency()).isEqualTo(1);
        assertThat(container.getCommonErrorHandler()).isInstanceOf(CommonContainerStoppingErrorHandler.class);
    }

    @DisplayName("구매 활동 listener는 이 factory로 등록돼 있고, 테스트에서는 자동 시작하지 않는다.")
    @Test
    void listenerIsRegisteredButNotAutoStarted() {
        // act
        MessageListenerContainer container = registry.getListenerContainer(OrderConfirmedListener.LISTENER_ID);

        // assert
        assertThat(container).isNotNull();
        assertThat(container.isRunning()).isFalse();
    }
}
