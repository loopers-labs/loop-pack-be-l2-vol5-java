package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.outbox.OutboxRelay;
import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxReceipt;
import com.loopers.domain.outbox.OutboxSender;
import com.loopers.support.kafka.OwnedStreamerProcess;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Delivers the actual incident outbox to a separate streamer JVM on a test-owned broker and activity database. */
final class CheckoutIncidentStream implements AutoCloseable {
    private final String topic;
    private final String group;
    private final List<TopicPartition> partitions;
    private final JdbcTemplate api;
    private final JdbcTemplate activities;
    private final OutboxRelay relay;
    private final OutboxSender sender;
    private final ObjectMapper json;
    private final AdminClient admin;
    private KafkaConsumer<String, String> inspector;
    private OwnedStreamerProcess streamer;
    private final Map<Long, Delivery> deliveries = new LinkedHashMap<>();
    private Delivery duplicate;

    private CheckoutIncidentStream(KafkaContainer kafka, String topic, String group, JdbcTemplate api, JdbcTemplate activities,
                                   OutboxRelay relay, OutboxSender sender, ObjectMapper json) {
        this.topic = topic;
        this.group = group;
        this.api = api;
        this.activities = activities;
        this.relay = relay;
        this.sender = sender;
        this.json = json;
        this.partitions = List.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1));
        this.admin = admin(kafka);
    }

    static void createTopic(KafkaContainer kafka, String topic) throws Exception {
        try (AdminClient setup = admin(kafka)) {
            setup.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    static CheckoutIncidentStream start(KafkaContainer kafka, MySQLContainer<?> activityDb, String topic, String group,
                                        Path streamerLog, JdbcTemplate api, JdbcTemplate activities, OutboxRelay relay,
                                        OutboxSender sender, ObjectMapper json) throws Exception {
        var stream = new CheckoutIncidentStream(kafka, topic, group, api, activities, relay, sender, json);
        try {
            stream.inspector = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
            stream.inspector.assign(stream.partitions);
            stream.inspector.seekToBeginning(stream.partitions);
            assertThat(stream.inspector.endOffsets(stream.partitions).values()).containsOnly(0L);
            stream.streamer = OwnedStreamerProcess.start(activityDb.getJdbcUrl(), activityDb.getUsername(),
                activityDb.getPassword(), kafka.getBootstrapServers(), topic, group, streamerLog);
            await().atMost(Duration.ofSeconds(45)).ignoreException(ExecutionException.class).untilAsserted(() -> {
                stream.streamer.assertAlive();
                var description = stream.admin.describeConsumerGroups(List.of(group)).all().get(5, TimeUnit.SECONDS).get(group);
                assertThat(description.state()).isEqualTo(ConsumerGroupState.STABLE);
                assertThat(description.members()).singleElement().satisfies(member ->
                    assertThat(member.assignment().topicPartitions()).containsExactlyInAnyOrderElementsOf(stream.partitions));
            });
            return stream;
        } catch (Exception | AssertionError failure) {
            try {
                stream.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Run precisely one configured relay batch so that the next ten pending events stay pending. */
    void publish(Map<Long, Instant> newlyConfirmed, long productId) throws Exception {
        int count = newlyConfirmed.size();
        assertThat(count).isPositive();
        var run = relay.runOnce();
        assertThat(run).isEqualTo(new OutboxRelay.Result(count, count, false));
        var records = read(count);
        assertThat(records).extracting(record -> Long.parseLong(record.key()))
            .containsExactlyInAnyOrderElementsOf(newlyConfirmed.keySet());
        for (var record : records) {
            long orderId = Long.parseLong(record.key());
            Published saved = published(orderId);
            assertThat(saved.topic()).isEqualTo(topic);
            assertThat(saved.messageKey()).isEqualTo(record.key());
            assertThat(saved.payload()).isEqualTo(record.value());
            var event = json.readTree(record.value());
            assertThat(event.required("eventId").asText()).isEqualTo(saved.eventId()).isEqualTo("order-confirmed:v1:" + orderId);
            assertThat(event.required("orderId").asLong()).isEqualTo(orderId);
            Instant confirmedAt = Instant.parse(event.required("confirmedAt").asText());
            assertThat(confirmedAt).isEqualTo(newlyConfirmed.get(orderId));
            assertThat(event.required("items").size()).isEqualTo(1);
            var item = event.required("items").get(0);
            assertThat(item.required("productId").asLong()).isEqualTo(productId);
            assertThat(item.required("quantity").asInt()).isEqualTo(1);
            assertThat(deliveries).doesNotContainKey(orderId);
            deliveries.put(orderId, new Delivery(orderId, saved.id(), saved.eventId(), confirmedAt,
                productId, 1, record.partition(), record.offset(), "APPLIED"));
        }
        verifyConsumption(records, "APPLIED");
        assertMatchingActivities();
        assertThat(api.queryForObject("SELECT COUNT(*) FROM outbox_message WHERE status='PUBLISHED'", Long.class))
            .isEqualTo((long) deliveries.size());
    }

    void replay(long orderId) throws Exception {
        var before = activityRows();
        var original = deliveries.get(orderId);
        assertThat(original).as("Previously delivered order").isNotNull();
        Published saved = published(orderId);
        assertThat(duplicate).isNull();
        var receipt = sender.send(new OutboxDeliveryAttempt(saved.id(), saved.eventId(), saved.topic(),
            saved.messageKey(), saved.payload(), saved.attemptCount())).get(12, TimeUnit.SECONDS);
        var record = read(1).getFirst();
        assertThat(record.key()).isEqualTo(saved.messageKey());
        assertThat(record.value()).isEqualTo(saved.payload());
        assertThat(record.partition()).isEqualTo(original.partition());
        assertThat(record.offset()).isGreaterThan(original.offset());
        assertThat(receipt).isEqualTo(new OutboxReceipt(topic, record.partition(), record.offset()));
        verifyConsumption(List.of(record), "DUPLICATE");
        assertThat(activityRows()).as("Both tables preserve their first row IDs and facts after replay").isEqualTo(before);
        assertMatchingActivities();
        assertThat(published(orderId)).isEqualTo(saved);
        duplicate = new Delivery(orderId, original.outboxId(), original.eventId(), original.confirmedAt(), original.productId(),
            original.quantity(), record.partition(), record.offset(), "DUPLICATE");
    }

    Map<String, Object> evidence() throws Exception {
        assertThat(duplicate).isNotNull();
        assertThat(relay.runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
        var ends = inspector.endOffsets(partitions);
        assertThat(ends.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(deliveries.size() + 1L);
        var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        Map<Integer, Long> committed = new LinkedHashMap<>();
        for (var partition : partitions) {
            assertThat(offsets).containsKey(partition);
            long offset = offsets.get(partition).offset();
            assertThat(offset).isEqualTo(ends.get(partition));
            committed.put(partition.partition(), offset);
        }
        return Map.of("topic", topic, "group", group, "applied", Map.copyOf(deliveries), "duplicate", duplicate,
            "committedOffsets", committed, "brokerRecordCount", ends.values().stream().mapToLong(Long::longValue).sum(),
            "activities", activityRows());
    }

    private void verifyConsumption(List<ConsumerRecord<String, String>> records, String outcome) {
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            streamer.assertAlive();
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            String log = streamer.log();
            for (var record : records) {
                var partition = new TopicPartition(topic, record.partition());
                assertThat(offsets).containsKey(partition);
                assertThat(offsets.get(partition).offset()).isGreaterThan(record.offset());
                assertThat(log).contains("purchase_activity_completed topic=" + topic + " partition=" + record.partition()
                    + " offset=" + record.offset() + " orderId=" + record.key() + " result=" + outcome);
            }
        });
    }

    private List<ConsumerRecord<String, String>> read(int count) {
        List<ConsumerRecord<String, String>> result = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (result.size() < count && System.nanoTime() < deadline) {
            streamer.assertAlive();
            inspector.poll(Duration.ofMillis(150)).forEach(result::add);
        }
        assertThat(result).hasSize(count);
        return result;
    }

    private Published published(long orderId) {
        return api.queryForObject("""
            SELECT id,event_id,topic,message_key,payload,attempt_count FROM outbox_message
            WHERE aggregate_type='Order' AND aggregate_id=? AND status='PUBLISHED'
            """, (r, n) -> new Published(r.getLong(1), r.getString(2), r.getString(3), r.getString(4),
            r.getString(5), r.getInt(6)), Long.toString(orderId));
    }

    private void assertMatchingActivities() {
        var rows = activityRows();
        assertThat(rows).extracting(Activity::orderId).containsExactlyInAnyOrderElementsOf(deliveries.keySet());
        assertThat(activities.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation", Long.class))
            .isEqualTo((long) deliveries.size());
        for (var row : rows) {
            var sent = deliveries.get(row.orderId());
            assertThat(row.eventId()).isEqualTo(sent.eventId());
            assertThat(row.productId()).isEqualTo(sent.productId());
            assertThat(row.quantity()).isEqualTo(sent.quantity());
            assertThat(row.confirmationTime()).isEqualTo(sent.confirmedAt());
            assertThat(row.activityTime()).isEqualTo(sent.confirmedAt());
        }
    }

    private List<Activity> activityRows() {
        return activities.query("""
            SELECT p.id,a.id,a.order_id,p.event_id,a.product_id,a.quantity,
                   p.confirmed_at_second,p.confirmed_at_nano,a.confirmed_at_second,a.confirmed_at_nano
            FROM processed_order_confirmation p JOIN product_purchase_activity a ON a.order_id=p.order_id
            ORDER BY a.order_id,a.product_id
            """, (r, n) -> new Activity(r.getLong(1), r.getLong(2), r.getLong(3), r.getString(4), r.getLong(5),
            r.getInt(6), Instant.ofEpochSecond(r.getLong(7), r.getInt(8)), Instant.ofEpochSecond(r.getLong(9), r.getInt(10))));
    }

    private static AdminClient admin(KafkaContainer kafka) {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000, AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000));
    }

    @Override
    public void close() {
        try {
            if (streamer != null) {
                streamer.close();
            }
        } finally {
            try {
                if (inspector != null) {
                    inspector.close(Duration.ofSeconds(5));
                }
            } finally {
                admin.close(Duration.ofSeconds(5));
            }
        }
    }

    private record Published(long id, String eventId, String topic, String messageKey, String payload, int attemptCount) { }
    record Delivery(long orderId, long outboxId, String eventId, Instant confirmedAt, long productId, int quantity,
                    int partition, long offset, String outcome) { }
    record Activity(long confirmationId, long activityId, long orderId, String eventId, long productId, int quantity,
                    Instant confirmationTime, Instant activityTime) { }
}
