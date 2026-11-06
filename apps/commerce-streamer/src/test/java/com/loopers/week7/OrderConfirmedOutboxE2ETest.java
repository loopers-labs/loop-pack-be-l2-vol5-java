package com.loopers.week7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceStreamerApplication;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OutboxDeliveryStore;
import com.loopers.application.order.OutboxKafkaSender;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.infrastructure.order.StockReservationJpaRepository;
import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.purchaseactivity.domain.PurchaseActivity;
import com.loopers.purchaseactivity.infrastructure.ProcessedOrderConfirmationJpaRepository;
import com.loopers.purchaseactivity.infrastructure.ProductPurchaseActivityJpaRepository;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = CommerceStreamerApplication.class, properties = {
        "payment-provider.connect-timeout=500ms", "payment-provider.read-timeout=2s",
        "payment-provider.lookup-retry.max-attempts=2", "payment-provider.lookup-retry.wait-duration=200ms",
        "payment-provider.lookup-retry.total-timeout=5s", "payment-provider.lookup-circuit.sliding-window-size=10",
        "payment-provider.lookup-circuit.minimum-number-of-calls=10", "payment-provider.lookup-circuit.failure-rate-threshold=50",
        "payment-provider.lookup-circuit.wait-duration-in-open-state=10s",
        "payment-provider.lookup-circuit.permitted-calls-in-half-open-state=1",
        "spring.kafka.listener.auto-startup=false", "purchase-activity.auto-startup=true", "order-events.partitions=2"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderConfirmedOutboxE2ETest {
    private static final String TOPIC = "order-events-w7-e2e-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity-w7-e2e-" + UUID.randomUUID();
    private static final LocalPgServer PG = LocalPgServer.start();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private OrderPaymentFixture fixtures;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private StockReservationJpaRepository reservations;
    @Autowired private ProcessedOrderConfirmationJpaRepository receipts;
    @Autowired private ProductPurchaseActivityJpaRepository activities;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private OutboxKafkaSender sender;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private DatabaseCleanUp cleanup;
    @MockitoSpyBean private OutboxDeliveryStore store;
    private AdminClient admin;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        new MySqlTestContainersConfig();
        new RedisTestContainersConfig();
        registry.add("spring.kafka.bootstrap-servers", KafkaTestContainersConfig::bootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", KafkaTestContainersConfig::bootstrapServers);
        registry.add("payment-provider.base-url", PG::baseUrl);
        registry.add("order-events.topic", () -> TOPIC);
        registry.add("purchase-activity.group-id", () -> GROUP);
    }

    @BeforeEach
    void prepare() {
        PG.reset();
        admin = AdminClient.create(Map.of("bootstrap.servers", KafkaTestContainersConfig.bootstrapServers()));
    }

    @AfterEach
    void cleanup() throws Exception {
        var stopped = new CountDownLatch(1);
        listeners.stop(stopped::countDown);
        assertThat(stopped.await(15, TimeUnit.SECONDS)).isTrue();
        try {
            cleanup.deleteAllEntities();
            admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
        } finally {
            admin.close(Duration.ofSeconds(3));
        }
    }

    @AfterAll
    static void closePg() {
        PG.close();
    }

    @Test
    void DB_커밋과_broker_확인_뒤_각각_재시작해도_상품별_구매는_한번이다() throws Exception {
        long userId = fixtures.createBuyerWithPoints(10_000);
        var first = fixtures.createProductWithStock(3_500, 10);
        var second = fixtures.createProductWithStock(2_000, 10);
        long orderId = createAndConfirmOrder(userId, first.getId(), second.getId());

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservations.findByOrderIdAndStatusOrderByProductIdAsc(orderId, StockReservationStatus.CONSUMED))
                .hasSize(2);
        assertThat(outboxStatus(orderId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select balance from point_balances where user_id = ?", Long.class, userId))
                .isEqualTo(10_000);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, first.getId()))
                .isEqualTo(8);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, second.getId()))
                .isEqualTo(9);
        assertThat(activities.findByOrderIdOrderByProductId(orderId)).isEmpty();
        assertThat(brokerRecordCount()).isZero();
        String eventId = "order-confirmed:v1:" + orderId;
        assertThat(jdbc.queryForObject("select event_id from order_outbox where order_id = ?",
                String.class, orderId)).isEqualTo(eventId);
        var confirmedAt = orders.findById(orderId).orElseThrow().getConfirmedAt();
        var expected = List.of(new PurchaseActivity(orderId, first.getId(), 2, confirmedAt),
                new PurchaseActivity(orderId, second.getId(), 1, confirmedAt));

        Instant firstRun = Instant.now().plusSeconds(10);
        var relayAfterCommit = new OrderOutboxRelay(store, sender, Clock.fixed(firstRun, ZoneOffset.UTC), bound -> 0);
        assertThat(relayAfterCommit).isNotSameAs(relay);
        doThrow(new IllegalStateException("stop after broker acknowledgement")).doCallRealMethod()
                .when(store).markPublished(anyString(), any(Instant.class));
        assertThatThrownBy(relayAfterCommit::runOnce).hasMessageContaining("stop after broker acknowledgement");
        assertThat(outboxStatus(orderId)).isEqualTo("PENDING");
        assertThat(brokerRecordCount()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(activities.findByOrderIdOrderByProductId(orderId).stream().map(row -> row.toDomain()).toList())
                    .containsExactlyElementsOf(expected);
            assertThat(receipts.findByEventIdOrOrderId(eventId, orderId)).hasSize(1);
        });

        var restartedRelay = new OrderOutboxRelay(store, sender,
                Clock.fixed(firstRun.plusSeconds(6), ZoneOffset.UTC), bound -> 0);
        restartedRelay.runOnce();

        assertThat(outboxStatus(orderId)).isEqualTo("PUBLISHED");
        awaitConsumerProcessing(orderId, eventId, expected);
        var records = readRecords();
        assertThat(records).hasSize(2);
        assertThat(records.get(0).key()).isEqualTo(Long.toString(orderId));
        assertThat(records.get(1).key()).isEqualTo(records.get(0).key());
        assertThat(records.get(1).value()).isEqualTo(records.get(0).value());
        assertThat(mapper.readValue(records.get(0).value(), OrderConfirmed.class).eventId()).isEqualTo(eventId);
        assertThat(PG.createRequestCount()).isEqualTo(1);
        assertThat(PG.transactions()).hasSize(1);
        assertThat(jdbc.queryForObject("select balance from point_balances where user_id = ?", Long.class, userId))
                .isEqualTo(10_000);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, first.getId()))
                .isEqualTo(8);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, second.getId()))
                .isEqualTo(9);
        assertThat(reservations.findByOrderIdAndStatusOrderByProductIdAsc(orderId, StockReservationStatus.CONSUMED))
                .hasSize(2);
    }

    private long createAndConfirmOrder(long userId, long firstProductId, long secondProductId) throws Exception {
        String body = mapper.writeValueAsString(Map.of("items", List.of(
                Map.of("productId", firstProductId, "quantity", 2),
                Map.of("productId", secondProductId, "quantity", 1))));
        var result = mvc.perform(post("/api/v1/orders").header("X-USER-ID", userId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        long orderId = mapper.readTree(result.getResponse().getContentAsString()).path("data").path("orderId").asLong();
        String paymentBody = """
                {"paymentAttemptKey":"017f22e2-79b0-7cc3-98c4-dc0c0c073981","cardType":"TEST","cardNo":"0000-0000-0000-0000"}
                """;
        mvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId).header("X-USER-ID", userId)
                .contentType(MediaType.APPLICATION_JSON).content(paymentBody)).andExpect(status().isAccepted());
        PG.succeedPaymentForOrder(payments.findByOrderId(orderId).orElseThrow().providerOrderId());
        mvc.perform(post("/api/v1/orders/{orderId}/payment/reconcile", orderId)
                .header("X-USER-ID", userId)).andExpect(status().isOk());
        return orderId;
    }

    private void awaitConsumerProcessing(long orderId, String eventId, List<PurchaseActivity> expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(activities.findByOrderIdOrderByProductId(orderId).stream().map(row -> row.toDomain()).toList())
                    .containsExactlyElementsOf(expected);
            assertThat(receipts.findByEventIdOrOrderId(eventId, orderId)).hasSize(1);
            assertThat(brokerRecordCount()).isEqualTo(2);
            for (int partition = 0; partition < 2; partition++) {
                long end = latestOffset(partition);
                if (end > 0) {
                    var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS);
                    var offset = offsets.get(new TopicPartition(TOPIC, partition));
                    assertThat(offset).isNotNull();
                    assertThat(offset.offset()).isEqualTo(end);
                }
            }
        });
    }

    private String outboxStatus(long orderId) {
        return jdbc.queryForObject("select status from order_outbox where order_id = ?", String.class, orderId);
    }

    private long brokerRecordCount() throws Exception {
        return latestOffset(0) + latestOffset(1);
    }

    private long latestOffset(int partition) throws Exception {
        var topicPartition = new TopicPartition(TOPIC, partition);
        return admin.listOffsets(Map.of(topicPartition, OffsetSpec.latest())).all().get(5, TimeUnit.SECONDS)
                .get(topicPartition).offset();
    }

    private List<ConsumerRecord<String, byte[]>> readRecords() {
        try (var inspector = new KafkaConsumer<String, byte[]>(Map.of(
                "bootstrap.servers", KafkaTestContainersConfig.bootstrapServers(),
                "group.id", "w7-inspect-" + UUID.randomUUID(), "enable.auto.commit", false,
                "key.deserializer", StringDeserializer.class, "value.deserializer", ByteArrayDeserializer.class))) {
            var partitions = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
            inspector.assign(partitions);
            inspector.seekToBeginning(partitions);
            var records = new ArrayList<ConsumerRecord<String, byte[]>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (records.size() < 2 && System.nanoTime() < deadline) {
                inspector.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }
}
