package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderEventPublication;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Kafka broker(테스트 fixture)로 확정 이벤트를 보내고, broker가 알려 준 위치에서 같은 record를 다시 읽어 확인한다.
 * 이 테스트는 단일 broker이므로 복제 장애에서의 {@code acks=all} 보장은 확인하지 못한다.
 */
class KafkaOrderEventPublisherTest {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final OrderConfirmedEvent EVENT = new OrderConfirmedEvent(
        OrderConfirmedEvent.EVENT_TYPE,
        OrderConfirmedEvent.SCHEMA_VERSION,
        OrderConfirmedEvent.eventIdOf(42L),
        42L,
        "2026-09-07T01:00:00Z",
        List.of(new OrderConfirmedEvent.Item(7L, 2), new OrderConfirmedEvent.Item(8L, 1))
    );

    private static KafkaTemplate<Object, Object> templateFor(String bootstrapServers, int maxBlockMs) {
        Map<String, Object> props = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
            JsonSerializer.ADD_TYPE_INFO_HEADERS, false,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs
        );
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    private static ConsumerRecord<String, byte[]> readAt(String topic, int partition, long offset) {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaBrokerFixture.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        );
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            TopicPartition target = new TopicPartition(topic, partition);
            consumer.assign(List.of(target));
            consumer.seek(target, offset);
            Instant deadline = Instant.now().plus(SEND_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    return record;
                }
            }
        }
        throw new AssertionError("broker가 알려 준 위치에서 record를 읽지 못했습니다: " + topic + "-" + partition + "@" + offset);
    }

    @DisplayName("실제 broker로 보내면, broker가 알려 준 위치에서 key와 이벤트 본문을 그대로 읽는다.")
    @Test
    void sendsRecordWhoseKeyAndBodyMatchTheEvent() throws Exception {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("order-events-test", 2);
        KafkaOrderEventPublisher publisher = new KafkaOrderEventPublisher(templateFor(KafkaBrokerFixture.bootstrapServers(), 5000), topic, SEND_TIMEOUT);

        // act
        OrderEventPublication publication = publisher.publish(EVENT);

        // assert
        assertThat(publication).isInstanceOf(OrderEventPublication.Sent.class);
        OrderEventPublication.Sent sent = (OrderEventPublication.Sent) publication;
        assertThat(sent.topic()).isEqualTo(topic);
        assertThat(sent.partition()).isBetween(0, 1);
        ConsumerRecord<String, byte[]> record = readAt(topic, sent.partition(), sent.offset());
        assertThat(record.key()).isEqualTo("42");
        JsonNode body = MAPPER.readTree(new String(record.value(), StandardCharsets.UTF_8));
        assertThat(body.get("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(body.get("eventId").asText()).isEqualTo("order-confirmed:v1:42");
        assertThat(body.get("orderId").asLong()).isEqualTo(42L);
        assertThat(body.get("confirmedAt").asText()).isEqualTo("2026-09-07T01:00:00Z");
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("productId").asLong()).isEqualTo(7L);
        assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(body.toString()).as("카드·사용자 정보를 담지 않는다").doesNotContain("card", "userId");
    }

    @DisplayName("같은 주문(key)을 다시 보내면, 같은 partition의 다음 offset으로 간다.")
    @Test
    void sameKeyGoesToSamePartition() {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("order-events-test", 2);
        KafkaOrderEventPublisher publisher = new KafkaOrderEventPublisher(templateFor(KafkaBrokerFixture.bootstrapServers(), 5000), topic, SEND_TIMEOUT);

        // act
        OrderEventPublication.Sent first = (OrderEventPublication.Sent) publisher.publish(EVENT);
        OrderEventPublication.Sent second = (OrderEventPublication.Sent) publisher.publish(EVENT);

        // assert
        assertThat(second.partition()).isEqualTo(first.partition());
        assertThat(second.offset()).isEqualTo(first.offset() + 1);
    }

    @DisplayName("broker에 닿지 못하면, 예외 없이 실패를 돌려준다.")
    @Test
    void returnsFailure_whenBrokerIsUnreachable() {
        // arrange: 아무도 듣지 않는 주소
        KafkaOrderEventPublisher publisher = new KafkaOrderEventPublisher(templateFor("127.0.0.1:1", 500), "order-events-unreachable", Duration.ofSeconds(3));

        // act
        OrderEventPublication publication = publisher.publish(EVENT);

        // assert
        assertThat(publication).isInstanceOf(OrderEventPublication.Failed.class);
    }
}
