package com.loopers.config;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KafkaConfigTest {

    @Autowired
    private ConsumerFactory<Object, Object> consumerFactory;

    @Autowired
    private ProducerFactory<Object, Object> producerFactory;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @DisplayName("consumer 는 자동 commit 을 끄고 byte[] 로 읽으며, producer 는 acks=all 과 멱등으로 보내고, 셋 모두 같은 broker 를 가리킨다.")
    @Test
    void usesEffectiveSettings_andSameBroker() {
        // arrange
        Map<String, Object> consumer = consumerFactory.getConfigurationProperties();
        Map<String, Object> producer = producerFactory.getConfigurationProperties();
        Map<String, Object> admin = kafkaAdmin.getConfigurationProperties();
        String broker = KafkaTestContainersConfig.bootstrapServers();

        // act & assert: 자동 commit 이 켜지면 DB 반영 전에 offset 이 진행해 업무가 유실된다.
        assertThat(String.valueOf(consumer.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG))).isEqualTo("false");
        assertThat(String.valueOf(consumer.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))).contains("ByteArrayDeserializer");
        assertThat(String.valueOf(producer.get(ProducerConfig.ACKS_CONFIG))).isEqualTo("all");
        assertThat(String.valueOf(producer.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG))).isEqualTo("true");
        assertThat(String.valueOf(consumer.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG))).contains(broker);
        assertThat(String.valueOf(producer.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))).contains(broker);
        assertThat(String.valueOf(admin.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG))).contains(broker);
    }

    @DisplayName("설정한 주소로 실제 broker 에 연결된다.")
    @Test
    void connectsToBroker() throws Exception {
        try (AdminClient client = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            assertThat(client.describeCluster().nodes().get(10, TimeUnit.SECONDS)).isNotEmpty();
        }
    }
}
