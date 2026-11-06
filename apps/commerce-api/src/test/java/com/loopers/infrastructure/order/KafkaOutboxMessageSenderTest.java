package com.loopers.infrastructure.order;

import com.loopers.application.order.OutboxMessageSender;
import com.loopers.application.order.OutboxSendResult;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * relay 전송기의 결과 분류 (7주차 설계 D27·D29·D33). 성공과 영구 오류는 실제 test broker 로, broker 불가는 닿지 않는 주소를 가리키는
 * 별도 인스턴스로 확인한다. 공유 broker 컨테이너는 멈추거나 재시작하지 않는다 (📄 p.7).
 */
@SpringBootTest(properties = "order-events.topic=" + KafkaOutboxMessageSenderTest.TOPIC)
class KafkaOutboxMessageSenderTest {

    static final String TOPIC = "order-events-KafkaOutboxMessageSenderTest";
    private static final String PAYLOAD_42 =
        "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:v1:42\",\"orderId\":42,"
            + "\"confirmedAt\":\"2026-11-07T01:00:00.123Z\",\"items\":[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]}";

    static {
        KafkaTestContainersConfig.createTopic(TOPIC, 1);
    }

    @Autowired
    private OutboxMessageSender sender;

    @DisplayName("저장된 key·payload 문자열을 그대로 보내면 PUBLISHED 이고 topic·partition·offset 이 채워지며, broker 의 record 는 key·bytes 가 입력과 같다.")
    @Test
    void publishesStoredKeyAndPayloadAsIs() {
        OutboxSendResult result = sender.send("42", PAYLOAD_42);

        assertThat(result.outcome()).isEqualTo(OutboxSendResult.Outcome.PUBLISHED);
        assertThat(result.topic()).isEqualTo(TOPIC);
        assertThat(result.partition()).isZero();
        assertThat(result.offset()).isNotNull();
        assertThat(result.detail()).isNull();

        List<ConsumerRecord<String, byte[]>> records = readAll();
        ConsumerRecord<String, byte[]> record = records.stream().filter(r -> r.offset() == result.offset()).findFirst().orElseThrow();
        assertThat(record.key()).isEqualTo("42");
        assertThat(new String(record.value(), StandardCharsets.UTF_8)).isEqualTo(PAYLOAD_42);
    }

    @DisplayName("producer 가 받을 수 없는 크기(RecordTooLarge)는 다시 보내도 같으므로 BLOCKED 이고 detail 에 원인 클래스가 남는다. payload 는 남지 않는다.")
    @Test
    void blocksPermanentError_recordTooLarge() {
        String oversized = "x".repeat(1_100_000); // max.request.size 기본 1MB 초과

        OutboxSendResult result = sender.send("43", oversized);

        assertThat(result.outcome()).isEqualTo(OutboxSendResult.Outcome.BLOCKED);
        assertThat(result.detail()).contains("RecordTooLargeException");
        assertThat(result.detail().length()).isLessThanOrEqualTo(KafkaOutboxMessageSender.DETAIL_MAX_LENGTH);
        assertThat(result.detail()).doesNotContain("xxxxx");
        assertThat(readAll()).noneMatch(r -> "43".equals(r.key()));
    }

    @DisplayName("닿지 않는 broker 면 대기 상한 안에 RETRY 로 끝나고(수신 0회로 단정하지 않음), detail 에 호스트:포트는 남지 않는다.")
    @Test
    void retriesWhenBrokerUnreachable() {
        Map<String, Object> props = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, 500L);
        KafkaTemplate<String, byte[]> deadTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        OutboxMessageSender deadSender = new KafkaOutboxMessageSender(deadTemplate, TOPIC, 500L);

        long started = System.currentTimeMillis();
        OutboxSendResult result = deadSender.send("44", PAYLOAD_42);

        assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
        assertThat(result.outcome()).isEqualTo(OutboxSendResult.Outcome.RETRY);
        assertThat(result.detail()).isNotBlank().doesNotContain("localhost:1");
        assertThat(readAll()).noneMatch(r -> "44".equals(r.key()));
    }

    @DisplayName("detail 정리: 호스트:포트는 가리고 1000자로 자른다.")
    @Test
    void sanitizesDetail() {
        String sanitized = KafkaOutboxMessageSender.sanitize("TimeoutException - broker kafka-1.internal:9092 unavailable " + "y".repeat(2000));

        assertThat(sanitized).startsWith("TimeoutException - broker <host:port> unavailable");
        assertThat(sanitized).hasSize(KafkaOutboxMessageSender.DETAIL_MAX_LENGTH);
    }

    /** 이 클래스 전용 topic 의 partition 0 을 처음부터 끝까지 읽는다. */
    private List<ConsumerRecord<String, byte[]>> readAll() {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "outbox-sender-test-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            long deadline = System.currentTimeMillis() + 10_000;
            while (consumer.position(partition) < end && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }
}
