package com.loopers.week6;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceStreamerApplication;
import com.loopers.application.order.OrderConfirmedPublisher;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.domain.order.Order;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CommerceStreamerApplication.class, properties = {
        "payment-provider.connect-timeout=500ms", "payment-provider.read-timeout=2s",
        "payment-provider.lookup-retry.max-attempts=2",
        "payment-provider.lookup-retry.wait-duration=200ms",
        "payment-provider.lookup-retry.total-timeout=5s",
        "payment-provider.lookup-circuit.sliding-window-size=10",
        "payment-provider.lookup-circuit.minimum-number-of-calls=10",
        "payment-provider.lookup-circuit.failure-rate-threshold=50",
        "payment-provider.lookup-circuit.wait-duration-in-open-state=10s",
        "payment-provider.lookup-circuit.permitted-calls-in-half-open-state=1",
        "spring.kafka.listener.auto-startup=false", "purchase-activity.auto-startup=true",
        "order-events.partitions=2"
})
@AutoConfigureMockMvc
@Import(Week6DirectPublicationFlowTest.FixedConfirmationClock.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Week6DirectPublicationFlowTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-29T01:00:00.123456Z");
    private static final String TOPIC = "order-events-w6-flow-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity-w6-flow-" + UUID.randomUUID();
    private static final LocalPgServer PG = LocalPgServer.start();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private OrderPaymentFixture fixtures;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentRepository payments;
    @Autowired private StockReservationJpaRepository reservations;
    @Autowired private ProcessedOrderConfirmationJpaRepository receipts;
    @Autowired private ProductPurchaseActivityJpaRepository activities;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private OrderConfirmedPublisher publisher;
    private AdminClient admin;

    @DynamicPropertySource
    static void configureIsolatedInfrastructure(DynamicPropertyRegistry registry) {
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
    void cleanOwnedResources() throws Exception {
        var stopped = new CountDownLatch(1);
        listeners.stop(stopped::countDown);
        assertThat(stopped.await(15, TimeUnit.SECONDS)).isTrue();
        try {
            cleanUp.deleteAllEntities();
            admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
        } finally {
            admin.close(Duration.ofSeconds(3));
        }
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @Test
    void 실제_PG_확정으로_미발행_주문과_정상_소비된_주문을_함께_재현한다() throws Exception {
        // Arrange
        PreparedOrder 미발행주문 = prepareThroughOrderApi("017f22e2-79b0-7cc3-98c4-dc0c0c073981");
        PreparedOrder 정상주문 = prepareThroughOrderApi("017f22e2-79b0-7cc3-98c4-dc0c0c073982");
        assertPendingWithoutActivity(미발행주문);
        assertPendingWithoutActivity(정상주문);
        assertThat(brokerRecordCount()).isZero();
        var sent = new AtomicReference<OrderConfirmedPublisher.PublishedRecord>();
        var orderWasCommittedBeforeFailure = new AtomicReference<Boolean>();
        doAnswer(invocation -> {
            OrderConfirmed event = invocation.getArgument(0);
            if (event.orderId() == 미발행주문.orderId()) {
                var freshRead = new TransactionTemplate(transactionManager);
                freshRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                freshRead.setReadOnly(true);
                orderWasCommittedBeforeFailure.set(freshRead.execute(ignored ->
                        orders.findById(event.orderId()).orElseThrow().getStatus() == OrderStatus.CONFIRMED));
                throw new IllegalStateException("W6 controlled hook: after commit, before Kafka send");
            }
            var metadata = (OrderConfirmedPublisher.PublishedRecord) invocation.callRealMethod();
            sent.set(metadata);
            return metadata;
        }).when(publisher).publish(any(OrderConfirmed.class));

        // Act
        confirmThroughReconcileApi(미발행주문);
        confirmThroughReconcileApi(정상주문);

        // Assert
        assertThat(orderWasCommittedBeforeFailure.get()).isTrue();
        assertConfirmedWithConsumedReservations(미발행주문);
        assertConfirmedWithConsumedReservations(정상주문);
        assertThat(activities.findByOrderIdOrderByProductId(미발행주문.orderId())).isEmpty();
        assertThat(receipts.findByOrderId(미발행주문.orderId())).isEmpty();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(storedActivities(정상주문)).containsExactlyElementsOf(expectedActivities(정상주문)));
        assertThat(receipts.findByOrderId(정상주문.orderId())).isPresent();
        assertThat(brokerRecordCount()).isEqualTo(1);
        var record = readPublishedRecord();
        var event = mapper.readValue(record.value(), OrderConfirmed.class);
        assertThat(event).isEqualTo(OrderConfirmed.confirmed(정상주문.orderId(), CONFIRMED_AT, 정상주문.items()));
        assertThat(record.key()).isEqualTo(Long.toString(정상주문.orderId()));
        assertThat(sent.get()).isEqualTo(new OrderConfirmedPublisher.PublishedRecord(
                record.topic(), record.partition(), record.offset()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(committedOffset(record.partition())).isEqualTo(record.offset() + 1));
        assertThat(PG.createRequestCount()).isEqualTo(2);
        writeObservedCases(미발행주문, 정상주문, record);
    }

    private PreparedOrder prepareThroughOrderApi(String attemptKey) throws Exception {
        long userId = fixtures.createBuyer();
        var firstProduct = fixtures.createProductWithStock(3_500, 10);
        var secondProduct = fixtures.createProductWithStock(2_000, 10);
        String orderBody = mapper.writeValueAsString(Map.of("items", List.of(
                Map.of("productId", firstProduct.getId(), "quantity", 1),
                Map.of("productId", secondProduct.getId(), "quantity", 1),
                Map.of("productId", firstProduct.getId(), "quantity", 1))));
        JsonNode created = mapper.readTree(mvc.perform(post("/api/v1/orders")
                        .header("X-USER-ID", userId).contentType(MediaType.APPLICATION_JSON).content(orderBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long orderId = created.path("data").path("orderId").asLong();
        assertThat(orderId).isPositive();
        String paymentBody = """
                {"paymentAttemptKey":"%s","cardType":"TEST","cardNo":"0000-0000-0000-0000"}
                """.formatted(attemptKey);
        mvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId)
                        .header("X-USER-ID", userId).contentType(MediaType.APPLICATION_JSON).content(paymentBody))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"));
        var payment = payments.findByOrderId(orderId).orElseThrow();
        PG.succeedPaymentForOrder(payment.providerOrderId());
        return new PreparedOrder(userId, orderId, payment.providerOrderId(), List.of(
                new OrderConfirmed.Item(firstProduct.getId(), 2), new OrderConfirmed.Item(secondProduct.getId(), 1)));
    }

    private void confirmThroughReconcileApi(PreparedOrder order) throws Exception {
        mvc.perform(post("/api/v1/orders/{orderId}/payment/reconcile", order.orderId())
                        .header("X-USER-ID", order.userId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));
    }

    private void assertPendingWithoutActivity(PreparedOrder prepared) {
        Order order = orders.findById(prepared.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(reservations.findByOrderIdAndStatusOrderByProductIdAsc(prepared.orderId(), StockReservationStatus.HELD))
                .hasSize(2);
        assertThat(activities.findByOrderIdOrderByProductId(prepared.orderId())).isEmpty();
        assertThat(receipts.findByOrderId(prepared.orderId())).isEmpty();
    }

    private void assertConfirmedWithConsumedReservations(PreparedOrder prepared) {
        Order order = orders.findById(prepared.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(reservations.findByOrderIdAndStatusOrderByProductIdAsc(prepared.orderId(), StockReservationStatus.CONSUMED)
                .stream().map(row -> new OrderConfirmed.Item(row.toDomain().productId(), row.toDomain().quantity())).toList())
                .containsExactlyElementsOf(prepared.items());
    }

    private List<PurchaseActivity> expectedActivities(PreparedOrder prepared) {
        return prepared.items().stream().map(item ->
                new PurchaseActivity(prepared.orderId(), item.productId(), item.quantity(), CONFIRMED_AT)).toList();
    }

    private List<PurchaseActivity> storedActivities(PreparedOrder prepared) {
        return activities.findByOrderIdOrderByProductId(prepared.orderId()).stream().map(row -> row.toDomain()).toList();
    }

    private long brokerRecordCount() throws Exception {
        return admin.listOffsets(Map.of(new TopicPartition(TOPIC, 0), OffsetSpec.latest(),
                        new TopicPartition(TOPIC, 1), OffsetSpec.latest())).all().get(10, TimeUnit.SECONDS)
                .values().stream().mapToLong(value -> value.offset()).sum();
    }

    private long committedOffset(int partition) throws Exception {
        var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                .get(new TopicPartition(TOPIC, partition));
        return committed == null ? -1 : committed.offset();
    }

    private ConsumerRecord<String, byte[]> readPublishedRecord() {
        try (var inspector = new KafkaConsumer<String, byte[]>(Map.of(
                "bootstrap.servers", KafkaTestContainersConfig.bootstrapServers(),
                "group.id", "w6-inspection-" + UUID.randomUUID(), "enable.auto.commit", false,
                "auto.offset.reset", "earliest", "key.deserializer", StringDeserializer.class,
                "value.deserializer", ByteArrayDeserializer.class))) {
            inspector.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : inspector.poll(Duration.ofMillis(200))) {
                    return record;
                }
            }
            throw new AssertionError("Actual broker record was not observed within 15 seconds");
        }
    }

    private void writeObservedCases(PreparedOrder missing, PreparedOrder delivered,
            ConsumerRecord<String, byte[]> record) throws Exception {
        var cases = new ArrayList<Map<String, Object>>();
        cases.add(observedCase("post-commit-pre-send-failure", missing, List.of()));
        cases.add(observedCase("published-and-consumed", delivered,
                List.of(Map.of("topic", record.topic(), "partition", record.partition(), "offset", record.offset()))));
        Map<String, Object> evidence = Map.of(
                "implementation", "W6 direct AFTER_COMMIT publication; no Outbox",
                "revision", System.getProperty("week6.revision", "unrecorded"),
                "workingTreeStatus", System.getProperty("week6.working-tree-status", "unknown"),
                "clock", CONFIRMED_AT.toString(), "bootstrapServers", KafkaTestContainersConfig.bootstrapServers(),
                "group", GROUP, "committedOffset", committedOffset(record.partition()), "cases", cases);
        Path destination = Path.of("build/reports/week6/direct-publication-cases.json");
        Files.createDirectories(destination.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), evidence);
        System.out.println("W6_DIRECT_PUBLICATION " + mapper.writeValueAsString(evidence));
    }

    private Map<String, Object> observedCase(String role, PreparedOrder prepared, List<Map<String, Object>> records) {
        var payment = payments.findByOrderId(prepared.orderId()).orElseThrow();
        return Map.of("role", role, "orderId", prepared.orderId(), "providerOrderId", payment.providerOrderId(),
                "transactionKey", payment.transactionKey(), "eventId", "order-confirmed:v1:" + prepared.orderId(),
                "confirmedAt", CONFIRMED_AT.toString(), "items", prepared.items(),
                "orderStatus", orders.findById(prepared.orderId()).orElseThrow().getStatus(),
                "reservations", reservations.findByOrderIdAndStatusOrderByProductIdAsc(
                        prepared.orderId(), StockReservationStatus.CONSUMED).stream()
                        .map(row -> Map.of("productId", row.toDomain().productId(), "quantity", row.toDomain().quantity(),
                                "status", "CONSUMED")).toList(),
                "delivery", Map.of("brokerRecords", records, "activities", storedActivities(prepared),
                        "receiptPresent", receipts.findByOrderId(prepared.orderId()).isPresent()));
    }

    private record PreparedOrder(long userId, long orderId, String providerOrderId, List<OrderConfirmed.Item> items) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedConfirmationClock {
        @Bean
        @Primary
        Clock week6FixedClock() {
            return Clock.fixed(CONFIRMED_AT, ZoneOffset.UTC);
        }
    }
}
