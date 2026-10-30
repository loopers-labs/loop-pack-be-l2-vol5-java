package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Price;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
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
    private static final Instant PLACED_AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-30T01:02:03.12Z");

    private final OrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderEventKafkaPublisherTest(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                                     ApplicationEventPublisher eventPublisher,
                                     KafkaTemplate<String, byte[]> kafkaTemplate, ObjectMapper objectMapper,
                                     DatabaseCleanUp databaseCleanUp) {
        this.orderRepository = orderRepository;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        kafkaTemplate.flush();
        databaseCleanUp.truncateAllTables();
    }

    private Long pendingOrderId() {
        Order draft = Order.draft(1L, List.of(
            OrderItem.of(8L, "셔츠", Price.of(1_000), OrderQuantity.of(1)),
            OrderItem.of(7L, "코트", Price.of(3_000), OrderQuantity.of(2))), PLACED_AT);
        draft.startPayment();
        return orderRepository.save(draft).getId();
    }

    private void confirm(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.completePayment(CONFIRMED_AT);
        orderRepository.save(order);
        order.pullEvents().forEach(eventPublisher::publishEvent);
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

    @DisplayName("EVT-002, EVT-012: 확정 TX 가 commit 되면 order-events 에 주문 id 를 key 로 한 record 가 하나 쌓이고, 확정 시각은 DB 에 남은 값과 같다")
    @Test
    void publishesAfterCommit() throws Exception {
        Long orderId = pendingOrderId();

        List<ConsumerRecord<String, byte[]>> records = recordsDuring(() ->
            transactionTemplate.executeWithoutResult(status -> confirm(orderId)));

        assertThat(records).hasSize(1);
        ConsumerRecord<String, byte[]> record = records.get(0);
        JsonNode value = objectMapper.readTree(record.value());
        Order stored = orderRepository.findById(orderId).orElseThrow();
        assertThat(record.key()).isEqualTo(String.valueOf(orderId));
        assertThat(value.get("eventId").asText()).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(value.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(Instant.parse(value.get("confirmedAt").asText())).isEqualTo(stored.getConfirmedAt());
    }

    @DisplayName("EVT-012: 확정 TX 가 rollback 되면 아무 record 도 쌓이지 않고 주문은 PENDING 그대로다")
    @Test
    void publishesNothingOnRollback() {
        Long orderId = pendingOrderId();

        List<ConsumerRecord<String, byte[]>> records = recordsDuring(() ->
            transactionTemplate.executeWithoutResult(status -> {
                confirm(orderId);
                status.setRollbackOnly();
            }));

        assertThat(records).isEmpty();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }
}
