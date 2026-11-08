package com.loopers.infrastructure.order.messaging;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** relay producer 가 설정 파일의 값을 실제로 받는지, 공용 template 를 건드리지 않는지 확인함 (7주차 D-107) */
@SpringBootTest
class KafkaOrderConfirmedSenderConfigTest {

    @Autowired
    private KafkaOrderConfirmedSender sender;

    @Autowired
    private KafkaTemplate<Object, Object> sharedKafkaTemplate;

    @DisplayName("relay producer 는 문자열 그대로 · acks=all · 잠정 timeout 으로 만들어진다.")
    @Test
    void appliesRelayProducerConfig() {
        Map<String, Object> config = sender.producerConfiguration();

        assertAll(
            () -> assertThat(config.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class),
            () -> assertThat(config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class),
            () -> assertThat(config.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all"),
            () -> assertThat(config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true),
            () -> assertThat(config.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)).isEqualTo(5_000),
            () -> assertThat(config.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)).isEqualTo(10_000),
            () -> assertThat(config.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(500L),
            () -> assertThat(String.valueOf(config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)))
                .contains(System.getProperty("spring.kafka.bootstrap-servers"))
        );
    }

    @DisplayName("relay producer 를 따로 만들어도 Boot 의 공용 KafkaTemplate 은 그대로 JsonSerializer 로 남는다.")
    @Test
    void keepsSharedTemplate() {
        assertThat(sharedKafkaTemplate.getProducerFactory().getConfigurationProperties().get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
            .isEqualTo(JsonSerializer.class);
    }
}
