package com.loopers.confg.kafka;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공용 kafka.yml 이 실제 Kafka client 설정으로 어떻게 읽히는지. yml 표기가 아니라 Kafka 의 ConsumerConfig·ProducerConfig 가 파싱한 값을 본다.
 */
@SpringBootTest
class KafkaClientConfigTest {

    @Autowired
    private ConsumerFactory<Object, Object> consumerFactory;
    @Autowired
    private ProducerFactory<Object, Object> producerFactory;
    @Autowired
    private KafkaAdmin kafkaAdmin;

    @DisplayName("consumer 는 자동 commit 이 꺼져 있고, value 를 byte[] 로 역직렬화한다.")
    @Test
    void consumerConfig() {
        ConsumerConfig config = new ConsumerConfig(consumerFactory.getConfigurationProperties());

        assertThat(config.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
        assertThat(config.getClass(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(ByteArrayDeserializer.class);
    }

    @DisplayName("producer 는 acks=all·idempotence 이고, send 블로킹·최종 실패 시간이 짧게 잡혀 있다.")
    @Test
    void producerConfig() {
        ProducerConfig config = new ProducerConfig(producerFactory.getConfigurationProperties());

        assertThat(config.getString(ProducerConfig.ACKS_CONFIG)).isIn("all", "-1");
        assertThat(config.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(config.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(5_000L);
        assertThat(config.getInt(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)).isEqualTo(30_000);
    }

    @DisplayName("producer·consumer·admin 이 모두 테스트 broker 의 같은 bootstrap 주소를 쓴다.")
    @Test
    void sameBootstrapServers() {
        String expected = KafkaTestContainersConfig.bootstrapServers();

        assertThat(new ConsumerConfig(consumerFactory.getConfigurationProperties()).getList(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG))
            .containsExactly(expected);
        assertThat(new ProducerConfig(producerFactory.getConfigurationProperties()).getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
            .containsExactly(expected);
        assertThat(new AdminClientConfig(kafkaAdmin.getConfigurationProperties()).getList(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG))
            .containsExactly(expected);
    }
}
