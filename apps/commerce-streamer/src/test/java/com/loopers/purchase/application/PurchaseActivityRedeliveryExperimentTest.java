package com.loopers.purchase.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.purchase.application.event.OrderConfirmedEvent;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "demo-kafka.listener.auto-startup=false",
    "purchase-activity.kafka.listener.auto-startup=false"
})
@Import(MySqlTestContainersConfig.class)
@DisplayName("Kafka DB commit 후 offset 미커밋 재전달 실험")
class PurchaseActivityRedeliveryExperimentTest {

    private static final String BOOTSTRAP_SERVERS = System.getenv()
        .getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:19092");
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-28T00:00:00Z");

    @Autowired private PurchaseActivityHandler purchaseActivityHandler;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> BOOTSTRAP_SERVERS);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", () -> BOOTSTRAP_SERVERS);
    }

    @Test
    @DisplayName("DB 저장 후 offset을 보류하면 같은 record가 재전달되고 업무 효과는 한 번만 남는다.")
    void redeliversSameRecordAfterDatabaseCommitWithoutDuplicatingPurchaseActivity() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String topic = "w6-redelivery-" + suffix;
        String groupId = "w6-redelivery-group-" + suffix;
        String eventId = "order-confirmed:v1:w6-" + suffix;
        long orderId = System.currentTimeMillis() * 1_000L + 17L;
        OrderConfirmedEvent event = confirmedOrder(eventId, orderId);
        TopicPartition targetPartition = null;
        boolean topicCreated = false;
        boolean groupOffsetCreated = false;

        try (AdminClient admin = AdminClient.create(adminProperties());
             KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProperties())) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                .all().get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            topicCreated = true;

            RecordMetadata sent = producer.send(new ProducerRecord<>(
                    topic,
                    event.orderId().toString(),
                    objectMapper.writeValueAsBytes(event)))
                .get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            targetPartition = new TopicPartition(topic, sent.partition());
            long targetOffset = sent.offset();
            TopicPartition assignmentTarget = targetPartition;

            Delivery firstDelivery;
            long offsetAfterFirstDelivery;
            try (KafkaConsumer<String, byte[]> firstConsumer = newConsumer(groupId, "first")) {
                CountDownLatch targetPartitionAssigned = new CountDownLatch(1);
                firstConsumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                    @Override
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    }

                    @Override
                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                        if (partitions.contains(assignmentTarget)) {
                            firstConsumer.pause(partitions);
                            targetPartitionAssigned.countDown();
                        }
                    }
                });
                awaitAssignment(firstConsumer, targetPartition, targetPartitionAssigned);
                firstConsumer.commitSync(Map.of(targetPartition, new OffsetAndMetadata(targetOffset)));
                groupOffsetCreated = true;
                firstConsumer.seek(targetPartition, targetOffset);
                firstConsumer.resume(List.of(targetPartition));

                ConsumerRecord<String, byte[]> record = pollRecord(firstConsumer, targetPartition, targetOffset);
                firstDelivery = delivery(record);
                purchaseActivityHandler.consume(parse(record));

                assertThat(countProcessedEvents(eventId)).isEqualTo(1);
                assertThat(countActivities(orderId)).isEqualTo(2);
                assertThat(committedOffset(admin, groupId, targetPartition)).isEqualTo(targetOffset);
                offsetAfterFirstDelivery = committedOffset(admin, groupId, targetPartition);
            }

            assertThat(committedOffset(admin, groupId, targetPartition)).isEqualTo(targetOffset);

            Delivery secondDelivery;
            try (KafkaConsumer<String, byte[]> secondConsumer = newConsumer(groupId, "second")) {
                secondConsumer.subscribe(List.of(topic));
                ConsumerRecord<String, byte[]> redelivered = pollRecord(
                    secondConsumer, targetPartition, targetOffset);
                secondDelivery = delivery(redelivered);

                purchaseActivityHandler.consume(parse(redelivered));
                assertThat(countProcessedEvents(eventId)).isEqualTo(1);
                assertThat(countActivities(orderId)).isEqualTo(2);

                secondConsumer.commitSync(Map.of(
                    targetPartition,
                    new OffsetAndMetadata(targetOffset + 1)
                ));
            }

            assertThat(firstDelivery).isEqualTo(secondDelivery);
            assertThat(committedOffset(admin, groupId, targetPartition)).isEqualTo(targetOffset + 1);
            assertThat(readActivities(orderId)).containsExactlyInAnyOrder(
                new PurchaseActivityRow(7001L, 2, CONFIRMED_AT),
                new PurchaseActivityRow(8001L, 1, CONFIRMED_AT)
            );

            System.out.printf(
                "Redelivery experiment topic=%s group=%s eventId=%s orderId=%d items=7001x2,8001x1 "
                    + "target=%s offsetTransition=%d->%d->%d "
                    + "deliveries=%s,%s processedEvents=%d activities=%d%n",
                topic,
                groupId,
                eventId,
                orderId,
                targetPartition,
                targetOffset,
                offsetAfterFirstDelivery,
                targetOffset + 1,
                firstDelivery,
                secondDelivery,
                countProcessedEvents(eventId),
                countActivities(orderId)
            );
        } finally {
            if (targetPartition != null) {
                jdbcTemplate.update("delete from product_purchase_activity where order_id = ?", orderId);
                jdbcTemplate.update("delete from processed_order_confirmation where event_id = ?", eventId);
            }
            if (topicCreated) {
                if (groupOffsetCreated) {
                    deleteConsumerGroup(groupId);
                }
                deleteTopic(topic);
            }
        }
    }

    private OrderConfirmedEvent confirmedOrder(String eventId, Long orderId) {
        return new OrderConfirmedEvent(
            "OrderConfirmed",
            1,
            eventId,
            orderId,
            CONFIRMED_AT,
            List.of(
                new OrderConfirmedEvent.Item(7001L, 2),
                new OrderConfirmedEvent.Item(8001L, 1)
            )
        );
    }

    private KafkaConsumer<String, byte[]> newConsumer(String groupId, String consumerName) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "w6-redelivery-" + consumerName);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(properties);
    }

    private Properties producerProperties() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "w6-redelivery-producer");
        return properties;
    }

    private Properties adminProperties() {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        return properties;
    }

    private void awaitAssignment(
        KafkaConsumer<String, byte[]> consumer,
        TopicPartition targetPartition,
        CountDownLatch assigned
    ) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && assigned.getCount() > 0) {
            consumer.poll(Duration.ofMillis(100));
        }
        assertThat(assigned.getCount()).as("target partition assignment callback").isZero();
        assertThat(consumer.assignment()).contains(targetPartition);
    }

    private ConsumerRecord<String, byte[]> pollRecord(
        KafkaConsumer<String, byte[]> consumer,
        TopicPartition targetPartition,
        long targetOffset
    ) {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(100))) {
                if (record.topic().equals(targetPartition.topic())
                    && record.partition() == targetPartition.partition()
                    && record.offset() == targetOffset) {
                    return record;
                }
            }
        }
        throw new AssertionError("Target record was not received within " + WAIT_TIMEOUT);
    }

    private OrderConfirmedEvent parse(ConsumerRecord<String, byte[]> record) throws Exception {
        OrderConfirmedEvent event = objectMapper.readValue(record.value(), OrderConfirmedEvent.class);
        assertThat(record.key()).isEqualTo(event.orderId().toString());
        return event;
    }

    private Delivery delivery(ConsumerRecord<String, byte[]> record) {
        return new Delivery(record.topic(), record.partition(), record.offset());
    }

    private long committedOffset(AdminClient admin, String groupId, TopicPartition partition) throws Exception {
        OffsetAndMetadata committed = admin.listConsumerGroupOffsets(groupId)
            .partitionsToOffsetAndMetadata()
            .get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            .get(partition);
        assertThat(committed).as("committed offset for %s", partition).isNotNull();
        return committed.offset();
    }

    private int countProcessedEvents(String eventId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from processed_order_confirmation where event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private int countActivities(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from product_purchase_activity where order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private List<PurchaseActivityRow> readActivities(long orderId) {
        return jdbcTemplate.query(
            "select product_id, quantity, confirmed_at from product_purchase_activity where order_id = ?",
            (resultSet, rowNum) -> new PurchaseActivityRow(
                resultSet.getLong("product_id"),
                resultSet.getInt("quantity"),
                resultSet.getTimestamp("confirmed_at", java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"))).toInstant()
            ),
            orderId
        );
    }

    private void deleteTopic(String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            admin.deleteTopics(List.of(topic)).all().get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private void deleteConsumerGroup(String groupId) throws Exception {
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            admin.deleteConsumerGroups(List.of(groupId)).all().get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private record Delivery(String topic, int partition, long offset) {
    }

    private record PurchaseActivityRow(long productId, int quantity, Instant confirmedAt) {
    }
}
