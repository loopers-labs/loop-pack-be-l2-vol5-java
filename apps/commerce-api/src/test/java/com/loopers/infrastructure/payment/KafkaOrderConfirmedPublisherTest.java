package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.OrderConfirmedPublishException;
import com.loopers.application.payment.OrderConfirmedPublisher;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderItem;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 실제 test broker 로 보낸 record 를 다시 읽어 이벤트 계약(6주차 설계 1절·D6·D9)을 확인한다.
 * 과제 p.2 "주문 42의 확정 이벤트 예시" JSON 을 commerce-streamer 의 파서 테스트와 같은 공통 샘플로 쓴다.
 */
@SpringBootTest(properties = "order-events.topic=" + KafkaOrderConfirmedPublisherTest.TOPIC)
class KafkaOrderConfirmedPublisherTest {

    static final String TOPIC = "order-events-KafkaOrderConfirmedPublisherTest";

    static final String ORDER_42_JSON = """
        {
          "eventType": "OrderConfirmed",
          "schemaVersion": 1,
          "eventId": "order-confirmed:v1:42",
          "orderId": 42,
          "confirmedAt": "2026-09-07T01:00:00Z",
          "items": [
            {"productId": 7, "quantity": 2},
            {"productId": 8, "quantity": 1}
          ]
        }
        """;

    private static final List<OrderItem> ORDER_42_ITEMS = List.of(new OrderItem(8L, 1, 1000L), new OrderItem(7L, 2, 1000L));

    static {
        // 앱은 topic 을 만들지 않으므로(설계 7절 결정) 컨텍스트가 뜨기 전에 격리 topic 을 만든다.
        try (AdminClient admin = AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("격리 topic 생성 실패: " + TOPIC, e);
        }
    }

    @Autowired
    private OrderConfirmedPublisher publisher;
    @Autowired
    private ObjectMapper objectMapper;

    /** 이 테스트 전용 group 으로 partition 0 을 처음부터 읽는다. assign 이라 group 배정을 기다리지 않는다. */
    private List<ConsumerRecord<String, byte[]>> readAll() {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "publisher-test-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long deadline = System.currentTimeMillis() + 10_000;
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            while (consumer.position(partition) < end && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, byte[]> polled = consumer.poll(Duration.ofMillis(500));
                polled.forEach(records::add);
            }
        }
        return records;
    }

    @DisplayName("주문 42 를 발행하면 key 는 \"42\" 이고 value JSON 은 과제 p.2 샘플과 같으며, confirmedAt 은 숫자가 아니라 ISO-8601 UTC 문자열이다.")
    @Test
    void publishesOrder42AsContractJson() throws Exception {
        OrderConfirmedEvent event = OrderConfirmedEvent.of(42L, ZonedDateTime.parse("2026-09-07T10:00:00+09:00[Asia/Seoul]"), ORDER_42_ITEMS);

        publisher.publish(event);

        List<ConsumerRecord<String, byte[]>> records = readAll();
        ConsumerRecord<String, byte[]> record = records.stream()
            .filter(r -> "42".equals(r.key()))
            .findFirst()
            .orElseThrow();
        JsonNode value = objectMapper.readTree(record.value());
        assertThat(value).isEqualTo(objectMapper.readTree(ORDER_42_JSON));
        assertThat(value.get("confirmedAt").isTextual()).as("confirmedAt 은 문자열").isTrue();
        assertThat(value.get("confirmedAt").asText()).isEqualTo("2026-09-07T01:00:00Z");
        assertThat(value.fieldNames()).toIterable()
            .containsExactly("eventType", "schemaVersion", "eventId", "orderId", "confirmedAt", "items");
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.partition()).isZero();
        assertThat(record.offset()).isGreaterThanOrEqualTo(0L);
    }

    @DisplayName("밀리초가 있는 확정 시각은 \"2026-09-07T01:00:00.123Z\" 문자열로 나간다.")
    @Test
    void serializesMillisAsIsoString() throws Exception {
        OrderConfirmedEvent event = OrderConfirmedEvent.of(43L, ZonedDateTime.parse("2026-09-07T01:00:00.123Z"), ORDER_42_ITEMS);

        publisher.publish(event);

        ConsumerRecord<String, byte[]> record = readAll().stream()
            .filter(r -> "43".equals(r.key()))
            .findFirst()
            .orElseThrow();
        JsonNode confirmedAt = objectMapper.readTree(record.value()).get("confirmedAt");
        assertThat(confirmedAt.isTextual()).isTrue();
        assertThat(confirmedAt.asText()).isEqualTo("2026-09-07T01:00:00.123Z");
        assertThat(new String(record.value(), StandardCharsets.UTF_8)).contains("\"confirmedAt\":\"2026-09-07T01:00:00.123Z\"");
    }

    @DisplayName("닿지 않는 broker 면 대기 상한 안에 producer 가 포기하므로 outcome=FAILED(실패 확정) 예외가 난다 (주문 상태는 호출자가 그대로 둔다).")
    @Test
    void failsWithinTimeout_whenBrokerUnreachable() {
        Map<String, Object> props = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, 500L);
        KafkaTemplate<String, byte[]> deadTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        OrderConfirmedPublisher deadPublisher = new KafkaOrderConfirmedPublisher(objectMapper, deadTemplate, TOPIC, 500L);
        OrderConfirmedEvent event = OrderConfirmedEvent.of(44L, ZonedDateTime.parse("2026-09-07T01:00:00Z"), ORDER_42_ITEMS);

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> deadPublisher.publish(event))
            .isInstanceOf(OrderConfirmedPublishException.class)
            .hasMessageContaining("outcome=FAILED")
            .hasMessageContaining("order-confirmed:v1:44")
            .hasMessageContaining("orderId=44")
            .extracting(e -> ((OrderConfirmedPublishException) e).getOutcome())
            .isEqualTo(OrderConfirmedPublishException.Outcome.FAILED);
        assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
        assertThat(readAll()).noneMatch(r -> "44".equals(r.key()));
    }

    /**
     * 실제 broker 로는 "버퍼에 남아 재시도 중" 상태를 재현하기 어려워 KafkaTemplate 을 mock 으로 바꿔 future 의 세 가지 끝을 흉내 낸다.
     */
    @DisplayName("send 결과 분류: ")
    @Nested
    class OutcomeClassification {

        private final KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
        private final OrderConfirmedPublisher mockedPublisher = new KafkaOrderConfirmedPublisher(objectMapper, template, TOPIC, 200L);
        private final OrderConfirmedEvent event = OrderConfirmedEvent.of(45L, ZonedDateTime.parse("2026-09-07T01:00:00Z"), ORDER_42_ITEMS);

        @DisplayName("future 가 metadata 로 완료되면 성공이고 예외가 없다.")
        @Test
        void success_whenFutureCompletes() {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, "45", new byte[0]);
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(TOPIC, 0), 7L, 0, 0L, 0, 0);
            given(template.send(eq(TOPIC), eq("45"), any(byte[].class)))
                .willReturn(CompletableFuture.completedFuture(new SendResult<>(record, metadata)));

            assertThatCode(() -> mockedPublisher.publish(event)).doesNotThrowAnyException();
        }

        @DisplayName("future 가 예외로 끝나면(producer 포기) outcome=FAILED 이고 원인이 보존된다.")
        @Test
        void failed_whenFutureCompletesExceptionally() {
            given(template.send(eq(TOPIC), eq("45"), any(byte[].class)))
                .willReturn(CompletableFuture.failedFuture(
                    new org.apache.kafka.common.errors.TimeoutException("Expiring 1 record(s): delivery.timeout.ms 소진")));

            assertThatThrownBy(() -> mockedPublisher.publish(event))
                .isInstanceOf(OrderConfirmedPublishException.class)
                .hasMessageContaining("outcome=FAILED")
                .hasMessageContaining("orderId=45")
                .hasCauseInstanceOf(org.apache.kafka.common.errors.TimeoutException.class)
                .extracting(e -> ((OrderConfirmedPublishException) e).getOutcome())
                .isEqualTo(OrderConfirmedPublishException.Outcome.FAILED);
        }

        @DisplayName("대기 상한 안에 future 가 끝나지 않으면 outcome=UNKNOWN(미확인) 이다. record 는 버퍼에 남아 뒤에서 발행될 수 있다.")
        @Test
        void unknown_whenFutureDoesNotCompleteWithinTimeout() {
            given(template.send(eq(TOPIC), eq("45"), any(byte[].class))).willReturn(new CompletableFuture<>());

            assertThatThrownBy(() -> mockedPublisher.publish(event))
                .isInstanceOf(OrderConfirmedPublishException.class)
                .hasMessageContaining("outcome=UNKNOWN")
                .hasMessageContaining("order-confirmed:v1:45")
                .extracting(e -> ((OrderConfirmedPublishException) e).getOutcome())
                .isEqualTo(OrderConfirmedPublishException.Outcome.UNKNOWN);
        }
    }
}
