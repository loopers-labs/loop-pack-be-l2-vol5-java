package com.loopers.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.confg.kafka.RetryThenStopErrorHandler;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {KafkaConfig.class, KafkaTestContainersConfig.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class KafkaConfigTest {

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private ConsumerFactory<Object, Object> consumerFactory;

    @Autowired
    @Qualifier(KafkaConfig.RECORD_LISTENER)
    private ConcurrentKafkaListenerContainerFactory<Object, Object> recordListenerContainerFactory;

    @DisplayName("테스트 broker 주소")
    @Nested
    class BootstrapServers {

        @DisplayName("producer·consumer·admin이 모두 테스트 컨테이너의 같은 주소 하나를 쓴다")
        @Test
        void allClientsUseTestContainerAddress() {
            String containerAddress = System.getProperty("spring.kafka.bootstrap-servers");

            Object producerServers = kafkaProperties.buildProducerProperties(null)
                .get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG);
            Object consumerServers = kafkaProperties.buildConsumerProperties(null)
                .get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG);
            Object adminServers = kafkaProperties.buildAdminProperties(null)
                .get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG);

            assertThat(containerAddress).isNotBlank();
            assertThat(String.valueOf(producerServers)).isEqualTo("[" + containerAddress + "]");
            assertThat(String.valueOf(consumerServers)).isEqualTo("[" + containerAddress + "]");
            assertThat(String.valueOf(adminServers)).isEqualTo(containerAddress);
        }
    }

    @DisplayName("기본 consumer 설정")
    @Nested
    class DefaultConsumer {

        @DisplayName("kafka.yml을 읽은 consumer의 실제 enable.auto.commit은 false이고 value는 byte 배열로 읽는다")
        @Test
        void autoCommitIsOffAndValueIsByteArray() {
            Map<String, Object> props = consumerFactory.getConfigurationProperties();
            ConsumerConfig consumerConfig = new ConsumerConfig(props);

            assertThat(consumerConfig.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
            assertThat(consumerConfig.getClass(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG))
                .isEqualTo(StringDeserializer.class);
            assertThat(consumerConfig.getClass(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
                .isEqualTo(ByteArrayDeserializer.class);
        }
    }

    @DisplayName("record listener 팩토리")
    @Nested
    class RecordListener {

        @DisplayName("한 번에 record 하나를 한 consumer로 받고 즉시 수동 commit하며 실패하면 재시도 후 컨테이너를 멈춘다")
        @Test
        void containerHandlesOneRecordAtATime() {
            ConcurrentMessageListenerContainer<Object, Object> container =
                recordListenerContainerFactory.createContainer("record-listener-config-check");

            assertThat(recordListenerContainerFactory.isBatchListener()).isFalse();
            assertThat(container.getConcurrency()).isEqualTo(1);
            assertThat(container.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
            assertThat(container.getCommonErrorHandler()).isInstanceOf(RetryThenStopErrorHandler.class);
        }

        @DisplayName("consumer는 auto commit을 끄고 batch 팩토리의 1MiB·5초 fetch 대기를 물려받지 않는다")
        @Test
        void consumerKeepsAutoCommitOffWithoutBatchFetchWait() {
            ConsumerConfig consumerConfig = new ConsumerConfig(
                recordListenerContainerFactory.getConsumerFactory().getConfigurationProperties());

            assertThat(consumerConfig.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
            assertThat(consumerConfig.getClass(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG))
                .isEqualTo(StringDeserializer.class);
            assertThat(consumerConfig.getClass(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
                .isEqualTo(ByteArrayDeserializer.class);
            assertThat(consumerConfig.getInt(ConsumerConfig.FETCH_MIN_BYTES_CONFIG)).isEqualTo(1);
            assertThat(consumerConfig.getInt(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG)).isEqualTo(500);
            assertThat(consumerConfig.getInt(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG))
                .isGreaterThan(10 * (int) (KafkaConfig.RECORD_RETRY_INTERVAL_MS * KafkaConfig.RECORD_RETRY_ATTEMPTS));
        }
    }
}
