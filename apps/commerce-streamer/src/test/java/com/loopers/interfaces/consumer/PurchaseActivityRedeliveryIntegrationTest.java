package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.purchase.ProductPurchaseActivityJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

@SpringBootTest(properties = "order.confirmed-event.auto-startup=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseActivityRedeliveryIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(PurchaseActivityRedeliveryIntegrationTest.class);
    private static final String TOPIC = "purchase-redelivery-" + UUID.randomUUID();
    private static final String GROUP = "purchase-redelivery-" + UUID.randomUUID();
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123456Z");
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(5);

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
    @Qualifier("orderActivityKafkaListenerContainerFactory")
    private ConcurrentKafkaListenerContainerFactory<String, byte[]> recordFactory;
    @Autowired
    private OrderConfirmedParser parser;
    @Autowired
    private PurchaseActivityHandler handler;
    @Autowired
    private ProcessedOrderConfirmationJpaRepository completions;
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
        assertThat(snapshot()).isEqualTo(new DatabaseSnapshot(List.of(), List.of()));
    }

    @AfterEach
    void tearDown() throws Exception {
        producer.flush();
        databaseCleanUp.truncateAllTables();
        AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        try {
            admin.deleteConsumerGroups(List.of(GROUP)).all().get(5, TimeUnit.SECONDS);
            admin.deleteTopics(List.of(TOPIC)).all().get(5, TimeUnit.SECONDS);
        } finally {
            admin.close(CLIENT_TIMEOUT);
        }
    }

    @DisplayName("DB commit 뒤 offset 미커밋으로 닫으면 새 consumer가 같은 record를 재전달받고 구매 효과는 유지된다.")
    @Test
    void redeliversSameRecordAfterDatabaseCommitWithoutOffsetCommit() throws Exception {
        var prefix = producer.send(TOPIC, 0, "41", payload(41L))
            .get(10, TimeUnit.SECONDS).getRecordMetadata();
        var target = producer.send(TOPIC, 0, "42", payload(42L))
            .get(10, TimeUnit.SECONDS).getRecordMetadata();
        TopicPartition partition = new TopicPartition(target.topic(), target.partition());
        long offset = target.offset();
        assertThat(offset).isGreaterThan(prefix.offset());

        AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        try {
            KafkaConsumer<String, byte[]> first = consumer("first");
            ConsumerRecord<String, byte[]> firstRecord;
            DatabaseSnapshot saved;
            String firstMember;
            AtomicInteger preparations = new AtomicInteger();
            try {
                first.subscribe(List.of(TOPIC), new ConsumerRebalanceListener() {
                    @Override
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {}

                    @Override
                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                        assertThat(partitions).containsExactly(partition);
                        assertThat(preparations.incrementAndGet()).isEqualTo(1);
                        first.seek(partition, offset);
                        assertThat(first.position(partition, CLIENT_TIMEOUT)).isEqualTo(offset);
                        first.commitSync(Map.of(partition, new OffsetAndMetadata(offset)), CLIENT_TIMEOUT);
                    }
                });
                firstRecord = readOne(first);
                assertRecord(firstRecord, partition, offset);
                assertThat(committed(admin, partition)).isEqualTo(offset);
                firstMember = first.groupMetadata().memberId();
                assertThat(firstMember).isNotBlank();

                handler.consume(parser.parseAndValidate(firstRecord.key(), firstRecord.value()));
                saved = snapshot();
                assertSavedFacts(saved);
                assertThat(first.position(partition, CLIENT_TIMEOUT)).isEqualTo(offset + 1);
                assertThat(committed(admin, partition)).isEqualTo(offset);
            } finally {
                first.close(CLIENT_TIMEOUT);
            }
            assertThat(preparations.get()).isEqualTo(1);
            assertThat(committed(admin, partition)).isEqualTo(offset);
            assertSingleCommit(first, partition, offset);
            assertThat(calls(first, "seek")).hasSize(1);
            assertBoundedClose(first);

            KafkaConsumer<String, byte[]> second = consumer("second");
            try {
                second.subscribe(List.of(TOPIC));
                ConsumerRecord<String, byte[]> secondRecord = readOne(second);
                assertRecord(secondRecord, partition, offset);
                assertThat(second.groupMetadata().memberId()).isNotBlank().isNotEqualTo(firstMember);
                assertThat(secondRecord.key()).isEqualTo(firstRecord.key());
                assertThat(secondRecord.value()).containsExactly(firstRecord.value());

                handler.consume(parser.parseAndValidate(secondRecord.key(), secondRecord.value()));
                assertThat(snapshot()).isEqualTo(saved);
                assertThat(committed(admin, partition)).isEqualTo(offset);
                assertThat(commitCalls(second)).isEmpty();

                second.commitSync(Map.of(partition, new OffsetAndMetadata(offset + 1)), CLIENT_TIMEOUT);
                assertThat(committed(admin, partition)).isEqualTo(offset + 1);
            } finally {
                second.close(CLIENT_TIMEOUT);
            }
            assertThat(committed(admin, partition)).isEqualTo(offset + 1);
            assertSingleCommit(second, partition, offset + 1);
            assertThat(mockingDetails(second).getInvocations())
                .noneMatch(invocation -> invocation.getMethod().getName().startsWith("seek"));
            assertBoundedClose(second);
            assertThat(snapshot()).isEqualTo(saved);

            log.info("W6_DB_COMMIT_REDELIVERY topic={}, partition={}, offset={}, group={}, deliveries=2, "
                    + "firstPosition={}, committedAtStart={}, committedAfterDatabase={}, committedAfterClose={}, "
                    + "committedAfterNoOp={}, finalCommitted={}, completionRows={}, activityRows={}, activities={}",
                TOPIC, partition.partition(), offset, GROUP, offset + 1, offset, offset, offset, offset,
                committed(admin, partition), saved.completions().size(), saved.activities().size(), saved.activities());
        } finally {
            admin.close(CLIENT_TIMEOUT);
        }
    }

    private KafkaConsumer<String, byte[]> consumer(String name) {
        Map<String, Object> properties = new HashMap<>(recordFactory.getConsumerFactory().getConfigurationProperties());
        assertThat(properties.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, GROUP + "-" + name);
        return spy(new KafkaConsumer<String, byte[]>(properties));
    }

    private static ConsumerRecord<String, byte[]> readOne(KafkaConsumer<String, byte[]> consumer) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var records = consumer.poll(Duration.ofMillis(100));
            if (!records.isEmpty()) {
                assertThat(records.count()).isEqualTo(1);
                return records.iterator().next();
            }
        }
        throw new AssertionError("deadline 안에 첫 record를 받지 못했습니다.");
    }

    private static void assertRecord(ConsumerRecord<String, byte[]> record, TopicPartition partition, long offset) {
        assertThat(record.topic()).isEqualTo(partition.topic());
        assertThat(record.partition()).isEqualTo(partition.partition());
        assertThat(record.offset()).isEqualTo(offset);
        assertThat(record.key()).isEqualTo("42");
    }

    private DatabaseSnapshot snapshot() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            entityManager.flush();
            entityManager.clear();
            List<CompletionRow> completionRows = completions.findAll().stream()
                .map(entity -> new CompletionRow(entity.getId(), entity.toDomain(),
                    entity.getCreatedAt().toInstant(), entity.getUpdatedAt().toInstant())).toList();
            List<ActivityRow> activityRows = activities.findAllByOrderIdOrderByProductIdAsc(42L).stream()
                .map(entity -> new ActivityRow(entity.getId(), entity.toDomain(),
                    entity.getCreatedAt().toInstant(), entity.getUpdatedAt().toInstant())).toList();
            assertThat(activities.count()).isEqualTo(activityRows.size());
            return new DatabaseSnapshot(completionRows, activityRows);
        });
    }

    private static void assertSavedFacts(DatabaseSnapshot saved) {
        assertThat(saved.completions()).hasSize(1);
        assertThat(saved.completions().getFirst().fact())
            .isEqualTo(ProcessedOrderConfirmation.create("order-confirmed:v1:42", 42L));
        assertThat(saved.activities()).extracting(ActivityRow::fact).containsExactly(
            ProductPurchaseActivity.create(42L, 7L, 2, CONFIRMED_AT),
            ProductPurchaseActivity.create(42L, 8L, 1, CONFIRMED_AT));
        assertThat(saved.activities()).allSatisfy(row -> assertThat(row.id()).isPositive());
        assertThat(saved.completions().getFirst().id()).isPositive();
    }

    private static long committed(AdminClient admin, TopicPartition partition) throws Exception {
        OffsetAndMetadata value = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
            .get(5, TimeUnit.SECONDS).get(partition);
        return value == null ? -1L : value.offset();
    }

    private static List<Invocation> calls(KafkaConsumer<String, byte[]> consumer, String method) {
        return mockingDetails(consumer).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals(method)).toList();
    }

    private static List<Invocation> commitCalls(KafkaConsumer<String, byte[]> consumer) {
        return mockingDetails(consumer).getInvocations().stream()
            .filter(invocation -> List.of("commitSync", "commitAsync").contains(invocation.getMethod().getName()))
            .toList();
    }

    private static void assertSingleCommit(KafkaConsumer<String, byte[]> consumer, TopicPartition partition, long offset) {
        assertThat(commitCalls(consumer)).singleElement().satisfies(invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("commitSync");
            assertThat(invocation.getArguments()).containsExactly(
                Map.of(partition, new OffsetAndMetadata(offset)), CLIENT_TIMEOUT);
        });
    }

    private static void assertBoundedClose(KafkaConsumer<String, byte[]> consumer) {
        assertThat(calls(consumer, "close")).singleElement().satisfies(invocation ->
            assertThat(invocation.getArguments()).containsExactly(CLIENT_TIMEOUT));
    }

    private static Map<String, Object> payload(long orderId) {
        return Map.of("eventType", "OrderConfirmed", "schemaVersion", 1,
            "eventId", "order-confirmed:v1:" + orderId, "orderId", orderId,
            "confirmedAt", CONFIRMED_AT.toString(), "items",
            List.of(Map.of("productId", 7L, "quantity", 2), Map.of("productId", 8L, "quantity", 1)));
    }

    private record DatabaseSnapshot(List<CompletionRow> completions, List<ActivityRow> activities) {}

    private record CompletionRow(Long id, ProcessedOrderConfirmation fact, Instant createdAt, Instant updatedAt) {}

    private record ActivityRow(Long id, ProductPurchaseActivity fact, Instant createdAt, Instant updatedAt) {}
}
