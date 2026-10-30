package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.config.PurchaseActivityListenerConfig;
import com.loopers.contract.OrderConfirmedContract;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "kafka.topics.order-events=" + OrderConfirmedListenerRetryTest.TOPIC,
    "kafka.groups.purchase-activity=" + OrderConfirmedListenerRetryTest.GROUP
})
class OrderConfirmedListenerRetryTest {

    static final String TOPIC = "order-events-retry";
    static final String GROUP = "purchase-activity-retry";
    private static final TopicPartition STUCK = new TopicPartition(TOPIC, 0);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final KafkaListenerEndpointRegistry registry;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderConfirmedListenerRetryTest(KafkaTemplate<String, byte[]> kafkaTemplate, KafkaListenerEndpointRegistry registry,
                                    ObjectMapper objectMapper, MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate,
                                    DatabaseCleanUp databaseCleanUp) {
        this.kafkaTemplate = kafkaTemplate;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeAll
    static void createTopic() {
        KafkaTestContainersConfig.createTopic(TOPIC, 2, Map.of());
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private byte[] confirmation(long orderId, int schemaVersion) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(OrderConfirmedContract.example());
        node.put("orderId", orderId);
        node.put("eventId", "order-confirmed:v1:" + orderId);
        node.put("schemaVersion", schemaVersion);
        return objectMapper.writeValueAsBytes(node);
    }

    private RecordMetadata send(int partition, long orderId, int schemaVersion) throws Exception {
        ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(TOPIC, partition,
            String.valueOf(orderId), confirmation(orderId, schemaVersion));
        return kafkaTemplate.send(producerRecord).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private Long committed(TopicPartition partition) throws Exception {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS)
                .get(partition);
            return offset == null ? null : offset.offset();
        }
    }

    private int receiptsOf(long orderId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from order_confirmation_receipt where order_id = ?", Integer.class, orderId);
    }

    private double retries() {
        return meterRegistry.find("purchase.activity.retry").counters().stream()
            .mapToDouble(counter -> counter.count())
            .sum();
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

    @DisplayName("ACT-010, KAFKA-006: 모르는 schemaVersion 은 보류하지 않고 그 partition 에서 다시 시도하며 offset 을 넘기지 않는다. 다른 partition 은 계속 처리되고 컨테이너는 멈추지 않는다")
    @Test
    void parksOnlyTheFailingPartition() throws Exception {
        double retriesBefore = retries();

        RecordMetadata unknown = send(0, 42L, 2);
        send(0, 44L, 1);
        send(1, 43L, 1);

        eventually(() -> receiptsOf(43L) == 1, Duration.ofSeconds(20));
        eventually(() -> retries() - retriesBefore >= 2, Duration.ofSeconds(20));
        Long committedStuck = committed(STUCK);

        System.out.printf("재시도 partition=%s, 모르는 버전 offset=%d, committed=%s, 재시도=%s%n",
            STUCK, unknown.offset(), committedStuck, retries() - retriesBefore);
        assertThat(committedStuck == null ? 0L : committedStuck).isLessThanOrEqualTo(unknown.offset());
        assertThat(receiptsOf(42L)).isZero();
        assertThat(receiptsOf(44L)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from order_event_hold", Integer.class)).isZero();
        assertThat(registry.getListenerContainer(PurchaseActivityListenerConfig.LISTENER_ID).isRunning()).isTrue();
    }
}
