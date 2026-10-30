package com.loopers.week6;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceApiApplication;
import com.loopers.order.application.OrderUseCase;
import com.loopers.order.infrastructure.kafka.OrderConfirmedContract;
import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.domain.PaymentGateway.ApprovalRequest;
import com.loopers.order.domain.StockReservationStatus;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.Stock;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.Point;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Calendar;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.TimeZone;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = CommerceApiApplication.class,
    properties = {
        "demo-kafka.listener.auto-startup=false",
        "purchase-activity.kafka.topic=order-events",
        "purchase-activity.kafka.listener.auto-startup=true"
    }
)
@Import(MySqlTestContainersConfig.class)
@DisplayName(" PG SUCCESS → 주문 commit → Kafka 발행 → streamer 저장 → offset commit")
class OrderConfirmationToPurchaseActivityE2eTest {

    private static final String TOPIC = "w6-order-confirmed-e2e-" + UUID.randomUUID();
    private static final String GROUP_ID = "week6-order-confirmation-e2e-" + UUID.randomUUID();
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-10-21T15:00:00+09:00");

    @Autowired private OrderUseCase orderUseCase;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
    @MockitoBean private PaymentGateway paymentGateway;
    @MockitoBean private OrderConfirmedContract orderConfirmedContract;

    private MessageListenerContainer listenerContainer;

    @DynamicPropertySource
    static void configureKafka(DynamicPropertyRegistry registry) {
        ensureTestTopic();
        registry.add("spring.kafka.bootstrap-servers", OrderConfirmationToPurchaseActivityE2eTest::bootstrapServers);
        registry.add("purchase-activity.kafka.group-id", () -> GROUP_ID);
        registry.add("purchase-activity.kafka.topic", () -> TOPIC);
    }

    @BeforeEach
    void waitForPurchaseConsumerAssignment() throws Exception {
        when(orderConfirmedContract.topic()).thenReturn(TOPIC);
        int partitionCount = topicPartitionCount(TOPIC);
        listenerContainer = listenerRegistry.getListenerContainer("order-confirmed-consumer");
        assertThat(listenerContainer).as("OrderConfirmed Kafka listener").isNotNull();
        waitForAssignment(listenerContainer, partitionCount);
    }

    @AfterEach
    void stopPurchaseConsumerAndDeleteTestGroup() throws Exception {
        if (listenerContainer != null && listenerContainer.isRunning()) {
            listenerContainer.stop();
        }
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            admin.deleteConsumerGroups(List.of(GROUP_ID)).all().get(10, TimeUnit.SECONDS);
            admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("PG SUCCESS 주문의 구매 활동을 저장하고 해당 record의 다음 offset을 commit한다.")
    void publishesConsumesPersistsAndCommitsTheEventOffset() throws Exception {
        // Given: streamer가 격리 topic의 partition을 배정받은 상태에서 PG SUCCESS를 준비한다.
        Scenario scenario = createScenario();
        String paymentKey = "week6-payment-" + UUID.randomUUID();
        String attemptKey = "week6-attempt-" + UUID.randomUUID();
        when(paymentGateway.approve(any())).thenAnswer(invocation -> approved(invocation.getArgument(0)));

        // 1. When: 기존 주문 확정 경로에서 PG SUCCESS를 적용한다.
        Order confirmed = orderUseCase.confirm(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey);
        verify(paymentGateway).approve(any());
        String eventId = "order-confirmed:" + scenario.orderId();

        // 2. Then: 실제 broker record를 찾아 topic·partition·offset·key를 확인한다.
        ConsumerRecord<String, byte[]> record = awaitEvent(TOPIC, eventId);
        TopicPartition targetPartition = new TopicPartition(TOPIC, record.partition());
        long expectedCommittedOffset = record.offset() + 1;
        assertThat(record.topic()).isEqualTo(TOPIC);

        // 3. And: streamer listener의 DB commit과 같은 consumer group의 offset commit을 기다린다.
        await(() -> countProcessed(eventId) == 1 && countActivities(scenario.orderId()) == 2);
        awaitCommittedOffset(targetPartition, expectedCommittedOffset);

        // 4. And: 주문 DB, Kafka payload, 구매 활동 DB가 같은 주문 사실을 담는지 비교한다.
        Order storedOrder = readOrder(scenario.orderId());
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(storedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(readReservationStatus(scenario.orderId())).isEqualTo(StockReservationStatus.CONSUMED);
        assertThat(record.key()).isEqualTo(scenario.orderId().toString());

        JsonNode payload = objectMapper.readTree(record.value());
        assertThat(payload.path("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(payload.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.path("eventId").asText()).isEqualTo(eventId);
        assertThat(payload.path("orderId").asLong()).isEqualTo(storedOrder.getId());
        assertThat(payload.path("confirmedAt").isTextual())
            .as("confirmedAt must follow the contract's ISO-8601 string format")
            .isTrue();
        Instant confirmedAt = readConfirmedAt(scenario.orderId());
        assertThat(Instant.parse(payload.path("confirmedAt").asText())).isEqualTo(confirmedAt);

        Map<Long, Integer> payloadItems = readPayloadItems(payload);
        assertThat(payloadItems).containsExactlyInAnyOrderEntriesOf(Map.of(
            scenario.firstProductId(), 2,
            scenario.secondProductId(), 1
        ));
        assertThat(readActivities(scenario.orderId())).containsExactlyInAnyOrder(
            new PurchaseActivityRow(scenario.firstProductId(), 2, confirmedAt),
            new PurchaseActivityRow(scenario.secondProductId(), 1, confirmedAt)
        );
        assertThat(committedOffset(targetPartition)).isGreaterThanOrEqualTo(expectedCommittedOffset);
    }

    private static String bootstrapServers() {
        return System.getenv().getOrDefault("BOOTSTRAP_SERVERS", "localhost:19092");
    }

    private static void ensureTestTopic() {
        try (AdminClient admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()))) {
            if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create isolated Week 6 E2E topic", exception);
        }
    }

    private Scenario createScenario() {
        return transactionTemplate.execute(status -> {
            Product first = new Product(1L, "week6 e2e first " + UUID.randomUUID(), 2_000L);
            Product second = new Product(1L, "week6 e2e second " + UUID.randomUUID(), 3_000L);
            entityManager.persist(first);
            entityManager.persist(second);
            entityManager.flush();
            entityManager.persist(new Stock(first.getId(), 5));
            entityManager.persist(new Stock(second.getId(), 5));
            User buyer = new User();
            entityManager.persist(buyer);
            entityManager.flush();
            entityManager.persist(new Point(buyer.getId(), 20_000L));
            Order order = new Order(buyer.getId(), List.of(
                OrderItem.of(first, 2),
                OrderItem.of(second, 1)
            ));
            entityManager.persist(order);
            entityManager.flush();
            return new Scenario(buyer.getId(), order.getId(), first.getId(), second.getId());
        });
    }

    private PaymentGateway.Approved approved(ApprovalRequest request) {
        return new PaymentGateway.Approved(
            request.paymentKey(), request.providerOrderId(), request.amount(), APPROVED_AT);
    }

    private Order readOrder(Long orderId) {
        entityManager.clear();
        return entityManager.find(Order.class, orderId);
    }

    private StockReservationStatus readReservationStatus(Long orderId) {
        return jdbcTemplate.queryForObject(
            "select stock_reservation_status from orders where id = ?",
            (resultSet, rowNum) -> StockReservationStatus.valueOf(resultSet.getString(1)), orderId);
    }

    private Instant readConfirmedAt(Long orderId) {
        Timestamp confirmedAt = jdbcTemplate.queryForObject(
            "select confirmed_at from orders where id = ?",
            (resultSet, rowNum) -> resultSet.getTimestamp("confirmed_at", utcCalendar()), orderId);
        return confirmedAt.toInstant();
    }

    private int countProcessed(String eventId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from processed_order_confirmation where event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private int countActivities(Long orderId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from product_purchase_activity where order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private List<PurchaseActivityRow> readActivities(Long orderId) {
        return jdbcTemplate.query(
            "select product_id, quantity, confirmed_at from product_purchase_activity "
                + "where order_id = ? order by product_id",
            (resultSet, rowNum) -> new PurchaseActivityRow(
                resultSet.getLong("product_id"),
                resultSet.getInt("quantity"),
                resultSet.getTimestamp("confirmed_at", utcCalendar()).toInstant()
            ),
            orderId
        );
    }

    private Map<Long, Integer> readPayloadItems(JsonNode payload) {
        Map<Long, Integer> items = new HashMap<>();
        payload.path("items").forEach(item -> items.put(
            item.path("productId").asLong(), item.path("quantity").asInt()));
        return items;
    }

    private ConsumerRecord<String, byte[]> awaitEvent(String topic, String eventId) throws Exception {
        Properties properties = new Properties();
        properties.putAll(adminProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "week6-e2e-observer-" + UUID.randomUUID());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        try (KafkaConsumer<String, byte[]> observer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = topicPartitions(topic);
            observer.assign(partitions);
            observer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : observer.poll(Duration.ofMillis(100))) {
                    JsonNode payload = objectMapper.readTree(record.value());
                    if (eventId.equals(payload.path("eventId").asText())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Kafka did not contain event " + eventId + " within " + WAIT_TIMEOUT);
    }

    private long committedOffset(TopicPartition partition) {
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            var offsets = admin.listConsumerGroupOffsets(GROUP_ID)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            var committed = offsets.get(partition);
            return committed == null ? -1 : committed.offset();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read committed offset for " + GROUP_ID, exception);
        }
    }

    private void awaitCommittedOffset(TopicPartition partition, long expectedOffset) throws Exception {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            while (System.nanoTime() < deadline) {
                var offsets = admin.listConsumerGroupOffsets(GROUP_ID)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
                var committed = offsets.get(partition);
                if (committed != null && committed.offset() >= expectedOffset) {
                    return;
                }
                Thread.sleep(100);
            }
        }
        assertThat(committedOffset(partition)).isGreaterThanOrEqualTo(expectedOffset);
    }

    private Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).as("E2E result within " + WAIT_TIMEOUT).isTrue();
    }

    private void waitForAssignment(MessageListenerContainer container, int partitionCount) throws Exception {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (container.getAssignedPartitions() != null
                && container.getAssignedPartitions().size() == partitionCount) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(container.getAssignedPartitions()).hasSize(partitionCount);
    }

    private int topicPartitionCount(String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            var description = admin.describeTopics(List.of(topic)).allTopicNames()
                .get(10, TimeUnit.SECONDS).get(topic);
            return description.partitions().size();
        }
    }

    private List<TopicPartition> topicPartitions(String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(adminProperties())) {
            var description = admin.describeTopics(List.of(topic)).allTopicNames()
                .get(10, TimeUnit.SECONDS).get(topic);
            return description.partitions().stream()
                .map(partition -> new TopicPartition(topic, partition.partition()))
                .toList();
        }
    }

    private Map<String, Object> adminProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        return properties;
    }

    private record Scenario(Long buyerId, Long orderId, Long firstProductId, Long secondProductId) {
    }

    private record PurchaseActivityRow(Long productId, int quantity, Instant confirmedAt) {
    }
}
