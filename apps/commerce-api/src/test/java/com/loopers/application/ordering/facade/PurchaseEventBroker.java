package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.ordering.gateway.OrderEventTransport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.jdbc.core.JdbcTemplate;

final class PurchaseEventBroker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate commerce;
    private final String bootstrap;
    private final String topic;
    private final OrderEventTransport transport;

    PurchaseEventBroker(JdbcTemplate commerce, String bootstrap, String topic, OrderEventTransport transport) {
        this.commerce = commerce;
        this.bootstrap = bootstrap;
        this.topic = topic;
        this.transport = transport;
    }

    List<Delivery> awaitPublished(List<Long> orderIds) {
        await().alias("20건의 Outbox 발행 · " + topic).atMost(Duration.ofSeconds(70)).untilAsserted(() ->
            assertThat(commerce.queryForList("SELECT status FROM order_event_outbox WHERE destination_topic = ?", String.class, topic))
                .hasSize(orderIds.size()).containsOnly("PUBLISHED"));
        List<Delivery> deliveries = commerce.query("""
            SELECT o.order_id, o.event_id, o.message_key, o.payload, o.published_topic, o.published_partition, o.published_offset,
                   r.created_at
              FROM order_event_outbox o
              JOIN order_records r ON r.order_id = o.order_id AND r.confirmation_event_id = o.event_id AND r.created_at = o.created_at
             WHERE o.destination_topic = ? AND o.event_type = 'OrderConfirmed' AND o.schema_version = 1 AND r.status = 'PAID'
             ORDER BY o.order_id
            """, (rs, index) -> readOutbox(rs), topic);
        assertThat(deliveries).extracting(Delivery::orderId).containsExactlyInAnyOrderElementsOf(orderIds);
        assertThat(deliveries).extracting(Delivery::eventId).doesNotHaveDuplicates();
        verifyBrokerRecords(deliveries);
        return deliveries;
    }

    private Delivery readOutbox(ResultSet rs) throws SQLException {
        long orderId = rs.getLong("order_id");
        UUID eventId = UUID.fromString(rs.getString("event_id"));
        byte[] payload = rs.getBytes("payload");
        try {
            var body = JSON.readTree(payload);
            assertThat(body.path("eventType").asText()).isEqualTo("OrderConfirmed");
            assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(body.path("orderId").asLong()).isEqualTo(orderId);
            assertThat(body.path("eventId").asText()).isEqualTo(eventId.toString());
            assertThat(body.path("items").size()).isOne();
            var item = body.path("items").get(0);
            Instant confirmedAt = Instant.parse(body.path("confirmedAt").asText());
            assertThat(confirmedAt).isEqualTo(rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
            assertThat(rs.getString("message_key")).isEqualTo(Long.toString(orderId));
            assertThat(rs.getString("published_topic")).isEqualTo(topic);
            Integer partition = rs.getObject("published_partition", Integer.class);
            Long offset = rs.getObject("published_offset", Long.class);
            assertThat(partition).as("주문 %s의 Outbox 발행 파티션", orderId).isNotNull().isNotNegative();
            assertThat(offset).as("주문 %s의 Outbox 발행 오프셋", orderId).isNotNull().isNotNegative();
            return new Delivery(orderId, eventId, confirmedAt, item.path("productId").asLong(), item.path("quantity").asInt(),
                topic, partition, offset, payload);
        } catch (IOException invalid) {
            throw new UncheckedIOException("Outbox payload를 읽을 수 없습니다. orderId=" + orderId, invalid);
        }
    }

    List<Delivery> redeliver(List<Delivery> originals) throws Exception {
        List<Delivery> replays = new ArrayList<>();
        for (Delivery original : originals) {
            var ack = transport.send(topic, Long.toString(original.orderId()), original.payload()).get(15, TimeUnit.SECONDS);
            assertThat(ack.topic()).isEqualTo(original.topic());
            assertThat(ack.partition()).isEqualTo(original.partition());
            assertThat(ack.offset()).isGreaterThan(original.offset());
            replays.add(new Delivery(original.orderId(), original.eventId(), original.confirmedAt(), original.productId(),
                original.quantity(), ack.topic(), ack.partition(), ack.offset(), original.payload()));
        }
        verifyBrokerRecords(replays);
        return List.copyOf(replays);
    }

    private void verifyBrokerRecords(List<Delivery> deliveries) {
        var positions = deliveries.stream().map(Delivery::position).toList();
        assertThat(positions).doesNotHaveDuplicates();
        var partitions = positions.stream().map(p -> new TopicPartition(topic, p.partition())).distinct().toList();
        Map<Position, ConsumerRecord<String, byte[]>> records = new HashMap<>();
        try (var reader = new KafkaConsumer<>(Map.of("bootstrap.servers", bootstrap, "enable.auto.commit", false),
                new StringDeserializer(), new ByteArrayDeserializer())) {
            reader.assign(partitions);
            reader.seekToBeginning(partitions);
            await().alias("Kafka 발행 좌표 · " + topic).pollInSameThread().atMost(Duration.ofSeconds(25)).until(() -> {
                reader.poll(Duration.ofMillis(250)).forEach(record -> records.put(new Position(record.partition(), record.offset()), record));
                return records.keySet().containsAll(positions);
            });
        }
        for (Delivery delivery : deliveries) {
            var record = records.get(delivery.position());
            assertThat(record.topic()).isEqualTo(delivery.topic());
            assertThat(record.key()).isEqualTo(Long.toString(delivery.orderId()));
            assertThat(record.value()).as("Kafka 원문 · 주문 %s", delivery.orderId()).isEqualTo(delivery.payload());
        }
    }

    List<PartitionCommit> awaitCommitted(String group, List<Delivery> deliveries) {
        Map<Integer, Long> required = new TreeMap<>();
        deliveries.forEach(row -> required.merge(row.partition(), row.offset() + 1, Math::max));
        try (var admin = Admin.create(Map.of("bootstrap.servers", bootstrap))) {
            return await().alias("구매 활동 소비 그룹 커밋 · " + group).atMost(Duration.ofSeconds(60)).until(() -> {
                try {
                    var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                    return required.entrySet().stream().map(entry -> {
                        var committed = offsets.get(new TopicPartition(topic, entry.getKey()));
                        return new PartitionCommit(entry.getKey(), entry.getValue(), committed == null ? -1 : committed.offset());
                    }).toList();
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof GroupIdNotFoundException) { return List.<PartitionCommit>of(); }
                    throw failure;
                }
            }, commits -> commits.size() == required.size() && commits.stream()
                .allMatch(commit -> commit.committedNextOffset() >= commit.requiredNextOffset()));
        }
    }

    record Delivery(long orderId, UUID eventId, Instant confirmedAt, long productId, int quantity,
                    String topic, int partition, long offset, @JsonIgnore byte[] payload) {
        private Position position() { return new Position(partition, offset); }
    }

    record PartitionCommit(int partition, long requiredNextOffset, long committedNextOffset) {}
    private record Position(int partition, long offset) {}
}
