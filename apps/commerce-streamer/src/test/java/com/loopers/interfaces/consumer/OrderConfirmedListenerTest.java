package com.loopers.interfaces.consumer;

import com.loopers.config.PurchaseActivityListenerConfig;
import com.loopers.contract.OrderConfirmedContract;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderConfirmedListenerTest {

    private static final String TOPIC = KafkaTestContainersConfig.ORDER_EVENTS;

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final KafkaListenerEndpointRegistry registry;
    private final ConcurrentKafkaListenerContainerFactory<String, byte[]> factory;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final HikariDataSource dataSource;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderConfirmedListenerTest(KafkaTemplate<String, byte[]> kafkaTemplate, KafkaListenerEndpointRegistry registry,
                               @Qualifier(PurchaseActivityListenerConfig.FACTORY)
                               ConcurrentKafkaListenerContainerFactory<String, byte[]> factory,
                               JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                               HikariDataSource dataSource, DatabaseCleanUp databaseCleanUp) {
        this.kafkaTemplate = kafkaTemplate;
        this.registry = registry;
        this.factory = factory;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.dataSource = dataSource;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private RecordMetadata send(String key, byte[] value) throws Exception {
        ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(TOPIC, key, value);
        return kafkaTemplate.send(producerRecord).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private long committed(TopicPartition partition) throws Exception {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets("purchase-activity")
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(partition);
            return offset == null ? -1L : offset.offset();
        }
    }

    private static byte[] example() {
        return OrderConfirmedContract.example().getBytes(StandardCharsets.UTF_8);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static void eventually(BooleanSupplier condition, Duration within) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(within + " 안에 조건이 채워지지 않았다");
            }
            Thread.sleep(100);
        }
    }

    @DisplayName("KAFKA-002, KAFKA-004, KAFKA-007, KAFKA-008: 구매 활동 listener 는 purchase-activity group 의 record listener 이고 partition 수만큼 스레드 3 개이며, BATCH ack, earliest, 자동 topic 생성 꺼짐, Kafka 기본 fetch 다")
    @Test
    void listenerSettings() {
        ConcurrentMessageListenerContainer<?, ?> container = (ConcurrentMessageListenerContainer<?, ?>)
            registry.getListenerContainer(PurchaseActivityListenerConfig.LISTENER_ID);
        ContainerProperties properties = container.getContainerProperties();
        Map<String, Object> consumer = factory.getConsumerFactory().getConfigurationProperties();

        assertThat(container.getConcurrency()).isEqualTo(3);
        assertThat(properties.getAckMode()).isEqualTo(ContainerProperties.AckMode.BATCH);
        assertThat(container.getGroupId()).isEqualTo("purchase-activity");
        assertThat(properties.isObservationEnabled()).isTrue();
        assertThat(consumer)
            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            .containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
            .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            .doesNotContainKeys(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG);
    }

    @DisplayName("ACT-010: streamer 의 DB 호출은 10 초 안에 응답이 없으면 예외가 된다")
    @Test
    void boundsDbSilence() {
        assertThat(dataSource.getDataSourceProperties()).containsEntry("socketTimeout", "10000");
    }

    @DisplayName("ACT-001, ACT-013: order-events 의 확정 이벤트를 받아 처리 기록과 구매 활동을 남긴다")
    @Test
    void recordsConfirmedOrder() throws Exception {
        send("42", example());

        eventually(() -> count("purchase_activity") == 2, Duration.ofSeconds(15));
        assertThat(count("order_confirmation_receipt")).isEqualTo(1);
    }

    @DisplayName("ACT-016: 다루지 않는 종류는 기록 없이 넘기고, 뒤의 확정 이벤트를 이어서 처리한다")
    @Test
    void skipsOtherEventTypes() throws Exception {
        send("42", OrderConfirmedContract.example().replace("\"OrderConfirmed\"", "\"OrderCancelled\"")
            .getBytes(StandardCharsets.UTF_8));
        send("42", example());

        eventually(() -> count("order_confirmation_receipt") == 1, Duration.ofSeconds(15));
        assertThat(count("order_event_hold")).isZero();
    }

    @DisplayName("ACT-009: 읽을 수 없는 record 는 보류하고 멈추지 않는다")
    @Test
    void holdsUnreadable() throws Exception {
        send("43", example());
        send("42", example());

        eventually(() -> count("order_confirmation_receipt") == 1, Duration.ofSeconds(15));
        assertThat(jdbcTemplate.queryForObject("select reason from order_event_hold", String.class))
            .isEqualTo("KEY_MISMATCH");
    }

    @DisplayName("KAFKA-006, EXP-009: 잠금 대기 초과는 1 초 뒤 다시 시도해, 잠금이 풀리면 사람 없이 반영되고, 컨테이너는 멈추지 않으며 committed offset 이 그 record 를 지난다")
    @Test
    void retriesLockWaitTimeout() throws Exception {
        CompletableFuture<Void> lockHolder = CompletableFuture.runAsync(() ->
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update("insert into order_confirmation_receipt (event_id, order_id, confirmed_at,"
                    + " items_digest, created_at, updated_at) values ('order-confirmed:v1:42', 42, now(6), '7:2,8:1',"
                    + " now(6), now(6))");
                sleep(Duration.ofMillis(4_500));
                status.setRollbackOnly();
            }));
        Thread.sleep(300);
        long startedAt = System.nanoTime();

        RecordMetadata sent = send("42", example());
        TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());

        eventually(() -> count("purchase_activity") == 2, Duration.ofSeconds(20));
        Duration recoveredIn = Duration.ofNanos(System.nanoTime() - startedAt);
        eventually(() -> {
            try {
                return committed(partition) > sent.offset();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, Duration.ofSeconds(20));
        lockHolder.get(10, TimeUnit.SECONDS);

        System.out.printf("EXP-009 record=%s@%d, 반영까지=%s, committed=%d%n",
            partition, sent.offset(), recoveredIn, committed(partition));
        assertThat(recoveredIn).isGreaterThan(Duration.ofSeconds(3));
        assertThat(registry.getListenerContainer(PurchaseActivityListenerConfig.LISTENER_ID).isRunning()).isTrue();
        assertThat(count("order_confirmation_receipt")).isEqualTo(1);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
