package com.loopers.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {KafkaConfig.class, JacksonAutoConfiguration.class})
class KafkaClientConfigTest {

    private final ProducerConfig producer;
    private final ConsumerConfig consumer;
    private final AdminClientConfig admin;

    @Autowired
    KafkaClientConfigTest(ProducerFactory<String, byte[]> producerFactory,
                          ConsumerFactory<Object, Object> consumerFactory,
                          KafkaProperties kafkaProperties) {
        this.producer = new ProducerConfig(producerFactory.getConfigurationProperties());
        this.consumer = new ConsumerConfig(consumerFactory.getConfigurationProperties());
        this.admin = new AdminClientConfig(kafkaProperties.buildAdminProperties(null));
    }

    @DisplayName("EVT-016: producer 는 acks=all 과 멱등을 쓰고, 재시도는 횟수가 아니라 delivery.timeout.ms 로 묶는다.")
    @Test
    void producerIsIdempotentAndRetriesUntilDeliveryTimeout() {
        assertThat(producer.getString(ProducerConfig.ACKS_CONFIG)).isIn("all", "-1");
        assertThat(producer.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(producer.getInt(ProducerConfig.RETRIES_CONFIG)).isEqualTo(Integer.MAX_VALUE);
    }

    @DisplayName("EVT-014: send() 가 막히는 시간은 최대 1 초다. 막히는 동안 확정 요청이 DB 커넥션을 쥐고 있기 때문이다.")
    @Test
    void producerBlocksAtMostOneSecond() {
        assertThat(producer.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(1_000L);
    }

    @DisplayName("EVT-003: producer 는 바이트를 보내고, payload 는 앱의 ObjectMapper 로 만든다.")
    @Test
    void producerSendsBytes() {
        assertThat(producer.getClass(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
            .isEqualTo(ByteArraySerializer.class);
    }

    @DisplayName("KAFKA-002: consumer 는 바이트를 받아서, 깨진 바이트도 원본 그대로 보류에 남길 수 있다.")
    @Test
    void consumerReceivesBytes() {
        assertThat(consumer.getClass(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
            .isEqualTo(ByteArrayDeserializer.class);
    }

    @DisplayName("KAFKA-003: 공용 consumer 설정은 auto commit 을 끈다. 테스트가 직접 만드는 consumer 도 이 값을 받는다.")
    @Test
    void consumerDoesNotAutoCommit() {
        assertThat(consumer.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
    }

    @DisplayName("KAFKA-001: producer, consumer, admin 이 같은 broker 주소를 본다.")
    @Test
    void clientsShareOneBroker() {
        assertThat(consumer.getList(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG))
            .isEqualTo(producer.getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertThat(admin.getList(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG))
            .isEqualTo(producer.getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
    }
}
