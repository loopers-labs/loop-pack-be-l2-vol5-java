package com.loopers.confg.kafka;

import com.loopers.utils.KafkaTopics;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 표기가 아니라 Kafka 클라이언트가 실제로 받는 값을 확인함 (6주차 D-91) */
@SpringBootTest
class KafkaSettingsTest {

    @Autowired
    private ConsumerFactory<Object, Object> consumerFactory;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private KafkaTopics kafkaTopics;

    @Value("${spring.application.name}")
    private String applicationName;

    @AfterEach
    void tearDown() {
        kafkaTopics.deleteCreated();
    }

    @DisplayName("공용 설정을 읽을 때, ")
    @Nested
    class SharedSettings {
        @DisplayName("consumer 는 자동 commit 을 끄고 value 를 byte 배열로 받는다.")
        @Test
        void consumerReceivesEffectiveValues() {
            // act
            Map<String, Object> config = consumerFactory.getConfigurationProperties();

            // assert
            assertAll(
                () -> assertThat(String.valueOf(config.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG))).isEqualTo("false"),
                () -> assertThat(config.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ByteArrayDeserializer.class),
                () -> assertThat(config).doesNotContainKey("enable-auto-commit")
            );
        }

        @DisplayName("admin 은 컨테이너 내부 주소가 아니라 테스트 broker 주소로 연결한다.")
        @Test
        void adminUsesTestBroker() {
            // act
            Object bootstrap = kafkaAdmin.getConfigurationProperties().get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);

            // assert
            assertThat(String.valueOf(bootstrap))
                .contains(System.getProperty("spring.kafka.bootstrap-servers"))
                .doesNotContain("kafka:9092");
        }

        @DisplayName("client-id 로 쓰이는 애플리케이션 이름은 commerce-streamer 다.")
        @Test
        void applicationNameIsStreamer() {
            assertThat(applicationName).isEqualTo("commerce-streamer");
        }
    }

    @DisplayName("테스트 broker 에서 topic 을 다룰 때, ")
    @Nested
    class Topics {
        @DisplayName("만든 topic 은 지정한 partition 수를 갖는다.")
        @Test
        void createsTopicWithPartitions() {
            // act
            String topic = kafkaTopics.create("settings", 3);

            // assert
            assertThat(kafkaTopics.partitionCount(topic)).isEqualTo(3);
        }

        @DisplayName("없는 topic 으로 보내면 자동으로 만들어지지 않고 max.block.ms 뒤 실패한다.")
        @Test
        void doesNotAutoCreateTopic() {
            // arrange
            String missing = "missing-" + System.nanoTime();
            Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.kafka.bootstrap-servers"),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000
            );

            // act & assert
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
                assertThatThrownBy(() -> producer.send(new ProducerRecord<>(missing, "key", "value")).get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
            }
            assertThat(kafkaTopics.names()).doesNotContain(missing);
        }
    }
}
