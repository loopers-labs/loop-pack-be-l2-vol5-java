package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderEventKafkaPublisherTest {

    private static final String TOPIC = KafkaTestContainersConfig.ORDER_EVENTS;

    private final OrderConfirmations orders;
    private final OrderEventPublishMetrics metrics;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderEventKafkaPublisherTest(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                                 ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry,
                                 KafkaTemplate<String, byte[]> kafkaTemplate, ObjectMapper objectMapper,
                                 DatabaseCleanUp databaseCleanUp) {
        this.orders = new OrderConfirmations(orderRepository, transactionTemplate, eventPublisher);
        this.metrics = new OrderEventPublishMetrics(meterRegistry);
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        kafkaTemplate.flush();
        databaseCleanUp.truncateAllTables();
    }

    private List<ConsumerRecord<String, byte[]>> recordsDuring(Runnable action) {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
            new StringDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                .map(info -> new TopicPartition(TOPIC, info.partition()))
                .toList();
            Map<TopicPartition, Long> before = consumer.endOffsets(partitions);

            action.run();
            kafkaTemplate.flush();

            Map<TopicPartition, Long> after = consumer.endOffsets(partitions);
            long expected = partitions.stream().mapToLong(p -> after.get(p) - before.get(p)).sum();
            consumer.assign(partitions);
            before.forEach(consumer::seek);

            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    @DisplayName("EVT-002, EVT-003, EVT-012: 확정 TX 가 commit 되면 order-events 에 주문 id 를 key 로, 종류와 버전을 payload 에 담은 record 가 하나 쌓이고, 확정 시각은 DB 에 남은 값과 같다")
    @Test
    void publishesAfterCommit() throws Exception {
        Long orderId = orders.pendingOrderId();

        List<ConsumerRecord<String, byte[]>> records = recordsDuring(() -> orders.commit(orderId));

        assertThat(records).hasSize(1);
        ConsumerRecord<String, byte[]> record = records.get(0);
        JsonNode value = objectMapper.readTree(record.value());
        assertThat(record.key()).isEqualTo(String.valueOf(orderId));
        assertThat(value.get("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(value.get("eventId").asText()).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(value.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(Instant.parse(value.get("confirmedAt").asText())).isEqualTo(orders.stored(orderId).getConfirmedAt());
    }

    @DisplayName("EVT-012: 확정 TX 가 rollback 되면 아무 record 도 쌓이지 않고 주문은 PENDING 그대로다")
    @Test
    void publishesNothingOnRollback() {
        Long orderId = orders.pendingOrderId();

        List<ConsumerRecord<String, byte[]>> records = recordsDuring(() -> orders.rollback(orderId));

        assertThat(records).isEmpty();
        assertThat(orders.statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @DisplayName("EVT-015: broker 가 받으면 성공 지표가 하나 늘고 실패 지표는 그대로다")
    @Test
    void countsSuccess() {
        Long orderId = orders.pendingOrderId();
        double successesBefore = metrics.successes();
        double failuresBefore = metrics.failures();

        orders.commit(orderId);
        kafkaTemplate.flush();

        assertThat(metrics.successes() - successesBefore).isEqualTo(1);
        assertThat(metrics.failures() - failuresBefore).isZero();
    }
}
