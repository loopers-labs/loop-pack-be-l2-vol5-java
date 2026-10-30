package com.loopers.kafka;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ProducerFactory;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KafkaConnectionTest {

    private final ProducerConfig producer;

    @Autowired
    KafkaConnectionTest(ProducerFactory<String, byte[]> producerFactory) {
        this.producer = new ProducerConfig(producerFactory.getConfigurationProperties());
    }

    @DisplayName("KAFKA-001: 앱 전체를 띄운 테스트의 producer 는 공유 fixture 의 broker 를 본다")
    @Test
    void producerUsesFixtureBroker() {
        assertThat(producer.getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
            .containsExactly(KafkaTestContainersConfig.bootstrapServers());
    }

    @DisplayName("EVT-014: commerce-api 도 공용 kafka.yml 의 producer 설정을 그대로 쓴다")
    @Test
    void producerFollowsSharedSettings() {
        assertThat(producer.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(1_000L);
        assertThat(producer.getClass(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
            .isEqualTo(ByteArraySerializer.class);
    }
}
