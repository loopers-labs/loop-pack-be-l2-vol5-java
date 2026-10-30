package com.loopers.purchase.application;

import com.loopers.purchase.application.event.OrderConfirmedEvent;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@EmbeddedKafka(
    partitions = 1,
    topics = OrderConfirmedKafkaConsumerIntegrationTest.ORDER_EVENTS_TOPIC,
    brokerProperties = "listeners=PLAINTEXT://localhost:0",
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@SpringBootTest(properties = {
    "demo-kafka.listener.auto-startup=false",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "purchase-activity.kafka.group-id=order-confirmed-consumer-integration-test"
})
@Import(MySqlTestContainersConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Kafka OrderConfirmed 수신·저장·ack 통합 테스트")
class OrderConfirmedKafkaConsumerIntegrationTest {

    static final String ORDER_EVENTS_TOPIC = "order-events";
    private static final String GROUP_ID = "order-confirmed-consumer-integration-test";
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-21T06:00:00Z");
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);

    @Autowired private EmbeddedKafkaBroker embeddedKafka;
    @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired private KafkaTemplate<Object, Object> kafkaTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MessageListenerContainer listenerContainer;

    @BeforeEach
    void prepareListenerAndDatabase() {
        dropFailureConstraint();
        jdbcTemplate.update("delete from product_purchase_activity");
        jdbcTemplate.update("delete from processed_order_confirmation");

        listenerContainer = listenerRegistry.getListenerContainer("order-confirmed-consumer");
        if (!listenerContainer.isRunning()) {
            listenerContainer.start();
        }
        ContainerTestUtils.waitForAssignment(listenerContainer, 1);
    }

    @AfterEach
    void stopListenerAndRestoreDatabaseSchema() {
        if (listenerContainer != null && listenerContainer.isRunning()) {
            listenerContainer.stop();
        }
        dropFailureConstraint();
    }

    @Test
    @Order(1)
    @DisplayName("Kafka에서 받은 이벤트를 DB에 저장한 뒤 다음 offset을 commit한다.")
    void storesReceivedEventAndCommitsNextOffsetAfterward() throws Exception {
        OrderConfirmedEvent event = confirmedOrder("event-42", 42L, 7L, 2, 8L, 1);

        SendResult<Object, Object> sent = kafkaTemplate.send(
            ORDER_EVENTS_TOPIC, "42", event).get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        TopicPartition partition = new TopicPartition(ORDER_EVENTS_TOPIC,
            sent.getRecordMetadata().partition());
        long expectedCommittedOffset = sent.getRecordMetadata().offset() + 1;

        await(() -> countActivities(42L) == 2
            && countProcessedEvents() == 1
            && committedOffset(partition) == expectedCommittedOffset);

        assertThat(readActivities(42L)).containsExactly(
            new PurchaseActivityRow(7L, 2, CONFIRMED_AT),
            new PurchaseActivityRow(8L, 1, CONFIRMED_AT)
        );
        assertThat(committedOffset(partition)).isEqualTo(expectedCommittedOffset);
    }

    @Test
    @Order(2)
    @DisplayName("DB 저장이 실패하면 전체 작업을 rollback하고 offset을 commit하지 않는다.")
    void rollsBackDatabaseWritesAndDoesNotCommitFailedRecord() throws Exception {
        jdbcTemplate.execute("""
            alter table product_purchase_activity
            add constraint ck_reject_product_8 check (product_id <> 8)
            """);
        OrderConfirmedEvent event = confirmedOrder("event-43", 43L, 7L, 2, 8L, 1);
        SendResult<Object, Object> sent = kafkaTemplate.send(
            ORDER_EVENTS_TOPIC, "43", event).get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        TopicPartition partition = new TopicPartition(ORDER_EVENTS_TOPIC,
            sent.getRecordMetadata().partition());

        await(() -> !listenerContainer.isRunning());

        assertThat(readActivities(43L)).isEmpty();
        assertThat(countProcessedEvents()).isZero();
        assertThat(committedOffset(partition)).isEqualTo(sent.getRecordMetadata().offset());
    }

    private OrderConfirmedEvent confirmedOrder(
        String eventId,
        Long orderId,
        Long firstProductId,
        int firstQuantity,
        Long secondProductId,
        int secondQuantity
    ) {
        return new OrderConfirmedEvent(
            "OrderConfirmed",
            1,
            eventId,
            orderId,
            CONFIRMED_AT,
            List.of(
                new OrderConfirmedEvent.Item(firstProductId, firstQuantity),
                new OrderConfirmedEvent.Item(secondProductId, secondQuantity)
            )
        );
    }

    private List<PurchaseActivityRow> readActivities(Long orderId) {
        return jdbcTemplate.query(
            "select product_id, quantity, confirmed_at "
                + "from product_purchase_activity where order_id = ? order by product_id",
            (resultSet, rowNum) -> new PurchaseActivityRow(
                resultSet.getLong("product_id"),
                resultSet.getInt("quantity"),
                resultSet.getTimestamp("confirmed_at", java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"))).toInstant()
            ),
            orderId
        );
    }

    private int countActivities(Long orderId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from product_purchase_activity where order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private int countProcessedEvents() {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from processed_order_confirmation", Integer.class);
        return count == null ? 0 : count;
    }

    private long committedOffset(TopicPartition partition) {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        try (AdminClient adminClient = AdminClient.create(properties)) {
            Map<TopicPartition, OffsetAndMetadata> offsets = adminClient.listConsumerGroupOffsets(GROUP_ID)
                .partitionsToOffsetAndMetadata()
                .get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            OffsetAndMetadata offset = offsets.get(partition);
            return offset == null ? -1 : offset.offset();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read committed consumer offset", exception);
        }
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).as("condition within " + WAIT_TIMEOUT).isTrue();
    }

    private void dropFailureConstraint() {
        Integer count = jdbcTemplate.queryForObject("""
            select count(*)
            from information_schema.table_constraints
            where constraint_schema = database()
              and table_name = 'product_purchase_activity'
              and constraint_name = 'ck_reject_product_8'
            """, Integer.class);
        if (count != null && count > 0) {
            jdbcTemplate.execute("alter table product_purchase_activity drop check ck_reject_product_8");
        }
    }

    private record PurchaseActivityRow(Long productId, int quantity, Instant confirmedAt) {
    }
}
