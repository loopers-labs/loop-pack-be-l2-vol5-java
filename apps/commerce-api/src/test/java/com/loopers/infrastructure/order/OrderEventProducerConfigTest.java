package com.loopers.infrastructure.order;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 컨텍스트에 만들어진 우리 전용 producer의 설정 값을 확인한다(docs/week6/decisions.md D-23, D-25).
 * 공용 kafka.yml의 value JsonSerializer가 섞여 들어오지 않았는지가 핵심이다.
 */
@SpringBootTest
class OrderEventProducerConfigTest {

    @Autowired
    @Qualifier(OrderEventKafkaConfig.PRODUCER_FACTORY)
    private ProducerFactory<String, String> orderEventProducerFactory;

    @DisplayName("주문 확정 이벤트 producer는 max.block.ms 5000, acks=all, key·value StringSerializer다.")
    @Test
    void producerConfig() {
        // act
        Map<String, Object> config = orderEventProducerFactory.getConfigurationProperties();

        // assert
        assertThat(config.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(5000);
        assertThat(config.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(config.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
        assertThat(config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
    }
}
