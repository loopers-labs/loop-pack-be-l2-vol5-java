package com.loopers.infrastructure.outbox;

import com.loopers.application.outbox.OutboxPublisher;
import com.loopers.domain.outbox.Outbox;
import com.loopers.support.kafka.TopicReader;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KafkaOutboxPublisherIntegrationTest {

    private static final String TOPIC = "outbox-publisher-" + UUID.randomUUID();

    @DynamicPropertySource
    static void topicProperty(DynamicPropertyRegistry registry) {
        registry.add("order-event.topic", () -> TOPIC);
    }

    @Autowired
    private OutboxPublisher outboxPublisher;

    @DisplayName("저장된 key 와 payload 를 그대로 보내고, broker 가 받은 뒤에야 반환한다.")
    @Test
    void publishesStoredKeyAndPayload_beforeReturning() {
        // given
        byte[] payload = {0x00, (byte) 0xFF, 0x7F, (byte) 0x80, 0x01};
        Outbox outbox = Outbox.record(
            "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", payload,
            ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]")
        );

        try (TopicReader reader = new TopicReader(KafkaTestContainersConfig.bootstrapServers(), TOPIC)) {
            // when
            outboxPublisher.publish(outbox);

            // then: 반환된 직후 broker 에 기록이 있어야 한다.
            assertThat(reader.recordsSinceOpen()).isEqualTo(1);
            ConsumerRecord<String, byte[]> record = reader.awaitRecords(1, Duration.ofSeconds(10)).get(0);
            assertThat(record.key()).isEqualTo("42");
            assertThat(record.value()).containsExactly(payload);
        }
    }
}
