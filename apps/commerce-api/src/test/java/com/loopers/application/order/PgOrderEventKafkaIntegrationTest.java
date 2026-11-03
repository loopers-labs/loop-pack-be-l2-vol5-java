package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.event.DomainEventPublisher;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.event.KafkaDomainEventPublisher;
import com.loopers.infrastructure.event.KafkaEventRoute;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Real PG HTTP, committed MySQL and Kafka wire records; this is not the streamer/offset-redelivery experiment. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(40)
class PgOrderEventKafkaIntegrationTest {
    private static final String CARD = "0000-0000-0000-0000";
    private static final String TOPIC = "order-events-test-" + UUID.randomUUID();
    private static final List<TopicPartition> PARTITIONS = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
    private static final LocalTestPg PG = createPg();

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    @Autowired private TestRestTemplate rest;
    @Autowired private OrderService orders;
    @Autowired private OrderPaymentCoordinator coordinator;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private KafkaTemplate<String, DomainEvent> kafka;
    @MockitoSpyBean private DomainEventPublisher publisher;

    private KafkaConsumer<String, String> consumer;
    private Map<TopicPartition, Long> initialOffsets;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("commerce.order-events.enabled", () -> true);
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", KAFKA::getBootstrapServers);
        registry.add("commerce.test-pg.enabled", () -> true);
        registry.add("commerce.test-pg.base-url", () -> PG.baseUri().toString());
        registry.add("commerce.test-pg.callback-url", () -> PG.callbackUri().toString());
    }

    @BeforeAll
    static void createOwnedTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void setUp() {
        PG.reset();
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(15));
        rest.getRestTemplate().setRequestFactory(requests);
        consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "publication-inspection-" + UUID.randomUUID(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
            ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
        // Inspect only this test's publications. Assignment/committed-offset recovery is a separate W6 experiment.
        consumer.assign(PARTITIONS);
        initialOffsets = consumer.endOffsets(PARTITIONS, Duration.ofSeconds(10));
        initialOffsets.forEach(consumer::seek);
    }

    @AfterEach
    void tearDown(CapturedOutput output) {
        try {
            if (consumer != null) {
                consumer.close(Duration.ofSeconds(5));
            }
        } finally {
            PG.releaseResponses();
            cleanup.truncateAllTables();
        }
        assertThat(output.getAll()).doesNotContain(CARD);
    }

    @AfterAll
    static void closePg() {
        PG.close();
        // The Testcontainers extension removes this class's broker; no shared topic/group/volume is deleted.
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "callback", "reconcile"})
    void eachConfirmationEntryPointPublishesTheSavedFactOnce(String first, CapturedOutput output) throws Exception {
        Fixture fixture = draft();
        if (first.equals("confirm")) {
            PG.nextCreateStatus("SUCCESS");
            paymentSuccess(confirm(fixture));
        } else {
            coordinator.startNew("alice", fixture.orderId(), fixture.key(), providerId(fixture), "TEST", CARD);
            assertThat(consumer.endOffsets(PARTITIONS, Duration.ofSeconds(10))).isEqualTo(initialOffsets);
            PG.setStatus(transactionKey(fixture), "SUCCESS");
            if (first.equals("callback")) {
                acknowledged(callback(fixture));
            } else {
                paymentSuccess(reconcile(fixture));
            }
        }

        ConsumerRecord<String, String> record = onlyPublishedRecord();
        assertWireFact(record, fixture);
        assertCommitted(fixture);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getAll().lines())
            .anyMatch(line -> line.contains("eventId=order-confirmed:v1:" + fixture.orderId())
                && line.contains("topic=" + TOPIC) && line.contains("partition=" + record.partition())
                && line.contains("offset=" + record.offset())));

        var firstFact = orders.getDetail("alice", fixture.orderId());
        paymentSuccess(confirm(fixture));
        acknowledged(callback(fixture));
        paymentSuccess(reconcile(fixture));

        assertThat(orders.getDetail("alice", fixture.orderId())).isEqualTo(firstFact);
        verify(publisher, times(1)).publish(any(OrderConfirmedEvent.class));
        assertThat(consumer.poll(Duration.ofMillis(300)).count()).isZero();
        assertThat(publishedCount()).isEqualTo(1L);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
        assertCommitted(fixture);
    }

    @Test
    void controlledPreSendFailureLeavesAConfirmedOrderAndNoBrokerRecord(CapturedOutput output) {
        Fixture fixture = draft();
        PG.nextCreateStatus("SUCCESS");
        // Fail before the real adapter can invoke send. A generic Kafka timeout would not prove record absence.
        doThrow(new IllegalStateException("test-only controlled pre-send gap"))
            .when(publisher).publish(any(OrderConfirmedEvent.class));

        paymentSuccess(confirm(fixture));
        assertCommitted(fixture);
        paymentSuccess(confirm(fixture));
        acknowledged(callback(fixture));
        paymentSuccess(reconcile(fixture));

        verify(publisher, times(1)).publish(any(OrderConfirmedEvent.class));
        assertThat(consumer.poll(Duration.ofMillis(300)).count()).isZero();
        assertThat(consumer.endOffsets(PARTITIONS, Duration.ofSeconds(10))).isEqualTo(initialOffsets);
        assertThat(output.getAll()).contains("domain_event_dispatch_failed", "eventId=order-confirmed:v1:" + fixture.orderId());
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
        assertCommitted(fixture);
    }

    @Test
    void actualProducerUsesTheIsolatedBootstrapAndChosenDeliverySettings() {
        Map<String, Object> properties = kafka.getProducerFactory().getConfigurationProperties();
        assertThat(properties.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG).toString()).contains(KAFKA.getBootstrapServers());
        assertThat(properties.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
        assertThat(properties.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(JsonSerializer.class);
        assertThat(properties.get(ProducerConfig.ACKS_CONFIG).toString()).isEqualTo("all");
        assertThat(properties.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG).toString()).isEqualTo("true");
        assertThat(properties.get(ProducerConfig.RETRIES_CONFIG).toString()).isEqualTo("3");
        assertThat(properties.get(ProducerConfig.MAX_BLOCK_MS_CONFIG).toString()).isEqualTo("1000");
        assertThat(properties.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG).toString()).isEqualTo("3000");
        assertThat(properties.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG).toString()).isEqualTo("10000");
        assertThat(properties.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION).toString()).isEqualTo("5");
        assertThat(kafka.getProducerFactory().transactionCapable()).isFalse();
    }

    @Test
    void realMetadataTimeoutIsLoggedWithoutChangingTheConfirmedHttpResponse(CapturedOutput output) {
        Fixture fixture = draft();
        PG.nextCreateStatus("SUCCESS");
        String missingTopic = "missing-order-events-" + UUID.randomUUID();
        // The broker is real and topic auto-creation is disabled: exercise the actual send failure path.
        var failingAdapter = new KafkaDomainEventPublisher(kafka,
            List.of(new KafkaEventRoute(OrderConfirmedEvent.class, missingTopic, true)));
        doAnswer(invocation -> {
            failingAdapter.publish(invocation.getArgument(0));
            return null;
        }).when(publisher).publish(any(OrderConfirmedEvent.class));

        paymentSuccess(confirm(fixture));

        assertCommitted(fixture);
        assertThat(output.getAll()).contains("domain_event_publication_failed",
            "eventId=order-confirmed:v1:" + fixture.orderId(), "topic=" + missingTopic);
        paymentSuccess(confirm(fixture));
        verify(publisher, times(1)).publish(any(OrderConfirmedEvent.class));
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    private ConsumerRecord<String, String> onlyPublishedRecord() {
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (received.isEmpty() && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(100)).forEach(received::add);
        }
        assertThat(received).hasSize(1);
        return received.getFirst();
    }

    private long publishedCount() {
        return consumer.endOffsets(PARTITIONS, Duration.ofSeconds(10)).entrySet().stream()
            .mapToLong(entry -> entry.getValue() - initialOffsets.get(entry.getKey())).sum();
    }

    private void assertWireFact(ConsumerRecord<String, String> record, Fixture fixture) throws Exception {
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo(Long.toString(fixture.orderId()));
        assertThat(record.headers().lastHeader("__TypeId__")).isNull();
        JsonNode payload = mapper.readTree(record.value());
        assertThat(payload.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
            "eventType", "schemaVersion", "eventId", "orderId", "confirmedAt", "items");
        assertThat(payload.path("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(payload.path("schemaVersion").isIntegralNumber()).isTrue();
        assertThat(payload.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.path("orderId").isIntegralNumber()).isTrue();
        assertThat(payload.path("orderId").asLong()).isEqualTo(fixture.orderId());
        assertThat(payload.path("eventId").asText()).isEqualTo("order-confirmed:v1:" + fixture.orderId());
        assertThat(Instant.parse(payload.path("confirmedAt").asText()))
            .isEqualTo(orders.getDetail("alice", fixture.orderId()).confirmedAt().toInstant());
        assertThat(payload.path("items")).hasSize(2);
        assertThat(payload.path("items").get(0).properties()).extracting(Map.Entry::getKey)
            .containsExactlyInAnyOrder("productId", "quantity");
        assertThat(payload.path("items").get(0).path("productId").asLong()).isEqualTo(fixture.firstProductId());
        assertThat(payload.path("items").get(0).path("quantity").asInt()).isEqualTo(3);
        assertThat(payload.path("items").get(1).path("productId").asLong()).isEqualTo(fixture.secondProductId());
        assertThat(payload.path("items").get(1).path("quantity").asInt()).isEqualTo(2);
        assertThat(record.value()).doesNotContain("card", "fingerprint", "providerOrderId", "transactionKey", "userId", CARD);
    }

    private Fixture draft() {
        Brand brand = brands.save(new Brand("Kafka 주문 브랜드"));
        Product first = products.save(new Product(brand, "상품 A", 100L, 10));
        Product second = products.save(new Product(brand, "상품 B", 100L, 5));
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(second.getId(), 2),
            new OrderQuantities.Item(first.getId(), 1), new OrderQuantities.Item(first.getId(), 2))).orderId();
        return new Fixture(orderId, first.getId(), second.getId(), UUID.randomUUID());
    }

    private void assertCommitted(Fixture fixture) {
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId()))
            .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId()))
            .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForList("SELECT reservation_status FROM order_item WHERE order_id=?", String.class, fixture.orderId()))
            .containsExactly("CONSUMED", "CONSUMED");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.firstProductId()))
            .isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.secondProductId()))
            .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(1000L);
        assertThat(orders.getDetail("alice", fixture.orderId()).paidAmount()).isEqualTo(500L);
    }

    private ResponseEntity<JsonNode> confirm(Fixture fixture) {
        return post("/api/v1/orders/" + fixture.orderId() + "/confirm", "alice",
            "{\"paymentAttemptKey\":\"" + fixture.key() + "\",\"cardType\":\"TEST\",\"cardNo\":\"" + CARD + "\"}");
    }

    private ResponseEntity<JsonNode> callback(Fixture fixture) {
        return post("/api/v1/payments/callback", null, "{\"providerOrderId\":\"" + providerId(fixture)
            + "\",\"transactionKey\":\"" + transactionKey(fixture) + "\",\"status\":\"PENDING\"}");
    }

    private ResponseEntity<JsonNode> reconcile(Fixture fixture) {
        return post("/api/v1/orders/" + fixture.orderId() + "/payment/reconcile", "alice", null);
    }

    private ResponseEntity<JsonNode> post(String path, String user, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (user != null) {
            headers.set("X-USER-ID", user);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode success(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(200);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        return response.getBody().path("data");
    }

    private void paymentSuccess(ResponseEntity<JsonNode> response) {
        JsonNode data = success(response);
        assertThat(data.path("paymentMethod").asText()).isEqualTo("PG");
        assertThat(data.path("payment").path("status").asText()).isEqualTo("SUCCESS");
    }

    private void acknowledged(ResponseEntity<JsonNode> response) {
        assertThat(success(response).path("acknowledged").asBoolean()).isTrue();
    }

    private String providerId(Fixture fixture) {
        return "LP-ORD-%010d".formatted(fixture.orderId());
    }

    private String transactionKey(Fixture fixture) {
        return jdbc.queryForObject("SELECT transaction_key FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId());
    }

    private static LocalTestPg createPg() {
        try {
            return new LocalTestPg();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private record Fixture(long orderId, long firstProductId, long secondProductId, UUID key) { }
}
