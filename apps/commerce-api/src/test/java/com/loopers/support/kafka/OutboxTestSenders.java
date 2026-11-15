package com.loopers.support.kafka;

import com.loopers.infrastructure.order.KafkaOrderOutboxSender;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * relay 테스트가 쓰는 실제 Kafka 발행기. 운영 설정과 같은 acks·멱등·문자열 직렬화로 테스트 broker의 격리 topic에 보낸다.
 * 닿지 않는 주소로 만든 발행기는 broker 장애를 흉내 낸다(메타데이터를 받지 못해 send가 실패한다).
 */
public final class OutboxTestSenders {

    private static final String UNREACHABLE = "127.0.0.1:1";

    private OutboxTestSenders() {
    }

    /** 테스트 broker의 topic으로 보내는 발행기. */
    public static KafkaOrderOutboxSender toBroker(String topic) {
        return new KafkaOrderOutboxSender(template(KafkaBrokerFixture.bootstrapServers(), 10_000), topic, Duration.ofSeconds(10));
    }

    /** broker에 닿지 않는 발행기. 0.5초 안에 실패를 돌려준다. */
    public static KafkaOrderOutboxSender unreachable(String topic) {
        return new KafkaOrderOutboxSender(template(UNREACHABLE, 500), topic, Duration.ofSeconds(3));
    }

    private static KafkaTemplate<String, String> template(String bootstrapServers, int maxBlockMs) {
        Map<String, Object> props = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs
        );
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer()));
    }
}
