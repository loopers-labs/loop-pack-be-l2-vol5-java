package com.loopers.interfaces.consumer;

import com.loopers.infrastructure.purchase.ProductPurchaseActivityJpaRepository;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.loopers.utils.DatabaseCleanUp;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "order.confirmed-event.auto-startup=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseActivityKafkaIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(PurchaseActivityKafkaIntegrationTest.class);
    private static final String TOPIC = "purchase-activity-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity-" + UUID.randomUUID();

    @DynamicPropertySource
    static void isolatedKafka(DynamicPropertyRegistry registry) {
        registry.add("order.confirmed-event.topic", () -> TOPIC);
        registry.add("order.confirmed-event.group-id", () -> GROUP);
    }

    @Autowired
    private KafkaTemplate<Object, Object> producer;
    @Autowired
    private KafkaAdmin kafkaAdmin;
    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProductPurchaseActivityJpaRepository activities;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        kafkaAdmin.createOrModifyTopics(TopicBuilder.name(TOPIC).partitions(1).replicas(1).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        MessageListenerContainer container = registry.getListenerContainer("purchase-activity-listener");
        container.stop();
        await(() -> !container.isRunning());
        producer.flush();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("실제 JSON record를 listener가 저장한 뒤 offset을 commit하고, 같은 사실의 새 record도 구매를 더하지 않는다.")
    @Test
    void consumesActualRecordsAndKeepsOnePurchaseEffect() throws Exception {
        Map<String, Object> payload = payload("event-42", 42L);
        var first = producer.send(TOPIC, "42", payload).get(10, TimeUnit.SECONDS).getRecordMetadata();
        TopicPartition partition = new TopicPartition(first.topic(), first.partition());
        MessageListenerContainer container = registry.getListenerContainer("purchase-activity-listener");

        container.start();
        await(() -> container.getAssignedPartitions() != null && container.getAssignedPartitions().size() == 1);

        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            await(() -> count("product_purchase_activity") == 2);
            await(() -> committed(admin, partition) == first.offset() + 1);
            assertSavedFacts();
            assertThat(count("processed_order_confirmation")).isEqualTo(1);

            var second = producer.send(TOPIC, "42", payload).get(10, TimeUnit.SECONDS).getRecordMetadata();
            await(() -> committed(admin, partition) == second.offset() + 1);

            assertSavedFacts();
            assertThat(count("processed_order_confirmation")).isEqualTo(1);
            log.info("W6_PURCHASE_ACTIVITY topic={}, partition={}, firstOffset={}, duplicateOffset={}, committed={}, completionRows={}, activityRows={}",
                TOPIC, partition.partition(), first.offset(), second.offset(), committed(admin, partition),
                count("processed_order_confirmation"), count("product_purchase_activity"));
        }
    }

    private void assertSavedFacts() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();
            var saved = activities.findAllByOrderIdOrderByProductIdAsc(42L).stream().map(entity -> entity.toDomain()).toList();
            assertThat(saved).hasSize(2);
            assertThat(saved).extracting(activity -> activity.productId()).containsExactly(7L, 8L);
            assertThat(saved).extracting(activity -> activity.quantity()).containsExactly(2, 1);
            assertThat(saved).allSatisfy(activity ->
                assertThat(activity.confirmedAt()).isEqualTo(Instant.parse("2026-09-07T01:00:00.123456Z")));
        });
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private long committed(AdminClient admin, TopicPartition partition) {
        try {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS).get(partition);
            return offset == null ? -1L : offset.offset();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> payload(String eventId, long orderId) {
        return Map.of("eventType", "OrderConfirmed", "schemaVersion", 1, "eventId", eventId, "orderId", orderId,
            "confirmedAt", "2026-09-07T01:00:00.123456Z", "items",
            List.of(Map.of("productId", 7L, "quantity", 2), Map.of("productId", 8L, "quantity", 1)));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
