package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.event.DomainEventPublisher;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.support.kafka.OwnedStreamerProcess;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Real API HTTP/PG/Kafka and a separate production streamer JVM with its own MySQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(90)
class PgOrderPurchaseActivityEndToEndTest {
    private static final Instant FIXED_PAYMENT_TIME = Instant.parse("2026-10-29T00:00:00.123456Z");
    private static final String CARD = "0000-0000-0000-0000";
    private static final String TOPIC = "order-events-e2e-" + UUID.randomUUID();
    private static final String GROUP = "purchase-activity-e2e-" + UUID.randomUUID();
    private static final List<TopicPartition> PARTITIONS = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
    private static final LocalTestPg PG = createPg();
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @Container
    private static final MySQLContainer<?> STREAMER_DB = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w6_streamer_e2e");

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;
    @TestBean(name = "orderPaymentClock", methodName = "fixedOrderPaymentClock")
    private Clock orderPaymentClock;
    @Autowired private TestRestTemplate rest;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private OrderService orders;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private DomainEventPublisher publisher;

    private AdminClient admin;
    private KafkaConsumer<String, String> inspector;
    private OwnedStreamerProcess streamer;

    static Clock fixedOrderPaymentClock() {
        return Clock.fixed(FIXED_PAYMENT_TIME, ZoneOffset.UTC);
    }

    @DynamicPropertySource
    static void isolatedExternalEndpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.order-events.enabled", () -> true);
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", KAFKA::getBootstrapServers);
        registry.add("commerce.test-pg.enabled", () -> true);
        registry.add("commerce.test-pg.base-url", () -> PG.baseUri().toString());
        registry.add("commerce.test-pg.callback-url", () -> PG.callbackUri().toString());
    }

    @BeforeAll
    static void initializeOwnedResources() throws Exception {
        // API DB comes only from the existing MySqlTestContainersConfig. Never use a developer DB.
        String schema = System.getProperty("w6.streamer.schema");
        assertThat(schema).isNotBlank();
        try (var connection = STREAMER_DB.createConnection("")) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(schema));
        }
        AdminClient client = newAdmin();
        try {
            client.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        } finally {
            client.close(Duration.ofSeconds(5));
        }
    }

    @BeforeEach
    void prepare() {
        PG.reset();
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(15));
        rest.getRestTemplate().setRequestFactory(requests);
        admin = newAdmin();
        inspector = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "e2e-wire-inspector-" + UUID.randomUUID(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
        inspector.assign(PARTITIONS);
        inspector.seekToBeginning(PARTITIONS);
    }

    @AfterEach
    void cleanOwnedResources(CapturedOutput output) throws Exception {
        try {
            if (streamer != null) {
                streamer.close(); // Stop the actual consumer before any DB/fixture cleanup.
            }
        } finally {
            try {
                if (inspector != null) {
                    inspector.close(Duration.ofSeconds(5));
                }
            } finally {
                try {
                    if (admin != null) {
                        admin.close(Duration.ofSeconds(5));
                    }
                } finally {
                    PG.releaseResponses();
                    cleanup.truncateAllTables();
                }
            }
        }
        assertThat(output.getAll()).doesNotContain(CARD);
        if (streamer != null) {
            assertThat(streamer.log()).doesNotContain(CARD);
        }
    }

    @AfterAll
    static void closePg() {
        PG.close();
    }

    @Test
    void normalDeliveryAndPreSendFailureKeepIndependentCommittedFacts(CapturedOutput output) throws Exception {
        streamer = OwnedStreamerProcess.start(STREAMER_DB.getJdbcUrl(), STREAMER_DB.getUsername(), STREAMER_DB.getPassword(),
            KAFKA.getBootstrapServers(), TOPIC, GROUP, Path.of("build", "reports", "w6-e2e", TOPIC, "streamer.log"));
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            streamer.assertAlive();
            assertThat(admin.listConsumerGroups().valid().get(5, TimeUnit.SECONDS))
                .extracting(listing -> listing.groupId()).contains(GROUP);
            var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(5, TimeUnit.SECONDS).get(GROUP);
            assertThat(group.state()).isEqualTo(ConsumerGroupState.STABLE);
            assertThat(group.members()).hasSize(1);
            assertThat(group.members().iterator().next().assignment().topicPartitions()).containsExactlyInAnyOrderElementsOf(PARTITIONS);
        });
        assertThat(activitySnapshot().confirmations()).isEmpty();
        assertThat(activitySnapshot().activities()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
            + "AND table_name='product_purchase_activity'", Integer.class)).isZero();

        Fixture fixture = draftThroughHttp();
        PG.nextCreateStatus("SUCCESS");
        JsonNode confirmed = paymentSuccess(confirm(fixture));
        ConsumerRecord<String, String> record = onlyPublishedRecord();
        Instant confirmedAt = orders.getDetail("alice", fixture.orderId()).confirmedAt().toInstant();
        assertThat(confirmedAt).isEqualTo(FIXED_PAYMENT_TIME);
        assertApiFacts(fixture);
        assertWire(record, fixture, confirmedAt);
        assertPg(fixture);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            streamer.assertAlive();
            assertActivity(fixture, confirmedAt);
            assertThat(committed(record)).isEqualTo(record.offset() + 1);
            assertThat(streamer.log()).contains("purchase_activity_completed topic=" + TOPIC
                + " partition=" + record.partition() + " offset=" + record.offset() + " orderId=" + fixture.orderId() + " result=APPLIED");
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getAll().lines())
            .anyMatch(line -> line.contains("domain_event_published") && line.contains("topic=" + TOPIC)
                && line.contains("partition=" + record.partition()) && line.contains("offset=" + record.offset())));

        ActivitySnapshot saved = activitySnapshot();
        Map<TopicPartition, Long> ends = inspector.endOffsets(PARTITIONS, Duration.ofSeconds(5));
        assertThat(ends.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1);
        assertThat(paymentSuccess(confirm(fixture))).isEqualTo(confirmed);
        verify(publisher, times(1)).publish(any(OrderConfirmedEvent.class));
        assertApiFacts(fixture);
        assertPg(fixture);
        assertThat(activitySnapshot()).isEqualTo(saved);
        assertThat(inspector.endOffsets(PARTITIONS, Duration.ofSeconds(5))).isEqualTo(ends);
        assertThat(committed(record)).isEqualTo(record.offset() + 1);
        String providerId = jdbc.queryForObject("SELECT provider_order_id FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId());
        String transactionKey = jdbc.queryForObject("SELECT transaction_key FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId());
        System.out.printf("normal_e2e_verified topic=%s group=%s partition=%d offset=%d orderId=%d providerOrderId=%s "
                + "transactionKey=%s eventId=order-confirmed:v1:%d confirmedAt=%s items=%d:3,%d:2 "
                + "pgCreates=%d completionRows=1 activityRows=2 committedOffset=%d repeatUnchanged=true%n",
            TOPIC, GROUP, record.partition(), record.offset(), fixture.orderId(), providerId, transactionKey,
            fixture.orderId(), confirmedAt, fixture.firstProductId(), fixture.secondProductId(), PG.createCalls(), committed(record));

        assertCommittedOrderSurvivesPreSendFailure(output, saved, ends);
        assertApiFacts(fixture); // The later failed publication also leaves the normal order intact.
    }

    private void assertCommittedOrderSurvivesPreSendFailure(CapturedOutput output, ActivitySnapshot delivered,
                                                           Map<TopicPartition, Long> ends) throws Exception {
        Fixture missing = draftThroughHttp();
        var completedOffsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        AtomicReference<CommittedObservation> observed = new AtomicReference<>();
        doAnswer(invocation -> {
            // AFTER_COMMIT still has thread-bound resources. A different connection must see committed facts.
            long writerConnection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            TransactionTemplate reader = new TransactionTemplate(transactionManager);
            reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            reader.setReadOnly(true);
            observed.set(reader.execute(status -> new CommittedObservation(writerConnection,
                jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class),
                orders.getDetail("alice", missing.orderId()).confirmedAt().toInstant(), apiSnapshot(missing))));
            // Stop before the real adapter is called: no async Kafka send can be pending for this event.
            throw new IllegalStateException("test-only controlled pre-send gap");
        }).when(publisher).publish(argThat(event -> event instanceof OrderConfirmedEvent confirmed
            && confirmed.orderId() == missing.orderId()));

        PG.nextCreateStatus("SUCCESS");
        JsonNode confirmed = paymentSuccess(confirm(missing));
        CommittedObservation atFailure = observed.get();
        assertThat(atFailure).isNotNull();
        assertThat(atFailure.readerConnection()).isNotEqualTo(atFailure.writerConnection());
        assertThat(atFailure.confirmedAt()).isEqualTo(FIXED_PAYMENT_TIME);
        assertApiFacts(missing);
        assertThat(apiSnapshot(missing)).usingRecursiveComparison().isEqualTo(atFailure.facts());
        assertThat(orders.getDetail("alice", missing.orderId()).confirmedAt().toInstant()).isEqualTo(atFailure.confirmedAt());
        assertThat(output.getAll().lines()).anyMatch(line -> line.contains("domain_event_dispatch_failed")
            && line.contains("eventId=order-confirmed:v1:" + missing.orderId() + " ")
            && line.contains("errorType=IllegalStateException"));
        assertMissingActivity(missing, delivered);
        assertThat(inspector.endOffsets(PARTITIONS, Duration.ofSeconds(5))).isEqualTo(ends);

        assertThat(paymentSuccess(confirm(missing))).isEqualTo(confirmed);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String transactionKey = jdbc.queryForObject("SELECT transaction_key FROM payment_attempt WHERE order_id=?", String.class, missing.orderId());
        String providerId = "LP-ORD-%010d".formatted(missing.orderId());
        String callbackBody = "{\"providerOrderId\":\"" + providerId + "\",\"transactionKey\":\"" + transactionKey + "\",\"status\":\"PENDING\"}";
        var callback = rest.exchange("/api/v1/payments/callback", HttpMethod.POST, new HttpEntity<>(callbackBody, headers), JsonNode.class);
        assertThat(callback.getStatusCode().value()).isEqualTo(200);
        assertThat(callback.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(callback.getBody().path("data").path("acknowledged").asBoolean()).isTrue();
        assertThat(paymentSuccess(post("/api/v1/orders/" + missing.orderId() + "/payment/reconcile", null))).isEqualTo(confirmed);

        verify(publisher, times(1)).publish(argThat(event -> event instanceof OrderConfirmedEvent confirmedEvent
            && confirmedEvent.orderId() == missing.orderId()));
        verify(publisher, times(2)).publish(any(OrderConfirmedEvent.class)); // Normal + failed attempt, no republish.
        assertThat(PG.createCalls()).isEqualTo(2); // One real transaction per order, including the normal control.
        assertThat(PG.transactionCount()).isEqualTo(2);
        assertThat(PG.requests()).filteredOn(request -> request.method().equals("POST") && providerId.equals(request.orderId()))
            .singleElement().satisfies(request -> {
                assertThat(request.amount()).isEqualTo(500L);
                assertThat(request.cardMatchesExpected()).isTrue();
            });
        assertApiFacts(missing);
        assertThat(apiSnapshot(missing)).usingRecursiveComparison().isEqualTo(atFailure.facts());
        assertThat(orders.getDetail("alice", missing.orderId()).confirmedAt().toInstant()).isEqualTo(atFailure.confirmedAt());
        assertMissingActivity(missing, delivered);
        assertThat(inspector.poll(Duration.ofMillis(300))).isEmpty();
        assertThat(inspector.endOffsets(PARTITIONS, Duration.ofSeconds(5))).isEqualTo(ends);
        assertThat(admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)).isEqualTo(completedOffsets);
        streamer.assertAlive();
        assertThat(admin.describeConsumerGroups(List.of(GROUP)).all().get(5, TimeUnit.SECONDS).get(GROUP).state())
            .isEqualTo(ConsumerGroupState.STABLE);
        assertThat(streamer.log().lines()).noneMatch(line -> line.contains("purchase_activity_completed")
            && line.contains("orderId=" + missing.orderId() + " "));
        System.out.printf("pre_send_gap_e2e_verified topic=%s group=%s orderId=%d providerOrderId=%s transactionKey=%s "
                + "eventId=order-confirmed:v1:%d confirmedAt=%s items=%d:3,%d:2 writerConnection=%d readerConnection=%d "
                + "pgCreatesTotal=%d orderPgCreates=1 brokerRecordsForOrder=0 completionRowsForOrder=0 activityRowsForOrder=0 "
                + "stock=7,3 points=1000 confirmCallbackReconcileUnchanged=true%n",
            TOPIC, GROUP, missing.orderId(), providerId, transactionKey, missing.orderId(), atFailure.confirmedAt(),
            missing.firstProductId(), missing.secondProductId(), atFailure.writerConnection(), atFailure.readerConnection(), PG.createCalls());
    }

    private void assertMissingActivity(Fixture missing, ActivitySnapshot delivered) throws Exception {
        ActivitySnapshot snapshot = activitySnapshot();
        assertThat(snapshot).isEqualTo(delivered); // Keep the already delivered order's PKs and every field.
        assertThat(snapshot.confirmations()).noneMatch(row -> row.get("order_id").equals(missing.orderId()));
        assertThat(snapshot.activities()).noneMatch(row -> row.get("order_id").equals(missing.orderId()));
    }

    private ApiSnapshot apiSnapshot(Fixture fixture) {
        return new ApiSnapshot(jdbc.queryForMap("SELECT * FROM `order` WHERE id=?", fixture.orderId()),
            jdbc.queryForMap("SELECT * FROM payment_attempt WHERE order_id=?", fixture.orderId()),
            jdbc.queryForList("SELECT * FROM order_item WHERE order_id=? ORDER BY id", fixture.orderId()),
            jdbc.queryForList("SELECT * FROM product WHERE id IN (?, ?) ORDER BY id", fixture.firstProductId(), fixture.secondProductId()),
            jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class));
    }

    private Fixture draftThroughHttp() {
        Brand brand = brands.save(new Brand("E2E 브랜드"));
        Product first = products.save(new Product(brand, "상품 A", 100L, 10));
        Product second = products.save(new Product(brand, "상품 B", 100L, 5));
        String body = "{\"items\":[{\"productId\":" + second.getId() + ",\"quantity\":2},"
            + "{\"productId\":" + first.getId() + ",\"quantity\":1},{\"productId\":" + first.getId() + ",\"quantity\":2}]}";
        ResponseEntity<JsonNode> response = post("/api/v1/orders", body);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("data").path("status").asText()).isEqualTo("DRAFT");
        return new Fixture(response.getBody().path("data").path("orderId").asLong(), first.getId(), second.getId(), UUID.randomUUID());
    }

    private ResponseEntity<JsonNode> confirm(Fixture fixture) {
        return post("/api/v1/orders/" + fixture.orderId() + "/confirm", "{\"paymentAttemptKey\":\"" + fixture.key()
            + "\",\"cardType\":\"TEST\",\"cardNo\":\"" + CARD + "\"}");
    }

    private ResponseEntity<JsonNode> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "alice");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode paymentSuccess(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        JsonNode data = response.getBody().path("data");
        assertThat(data.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(data.path("paymentMethod").asText()).isEqualTo("PG");
        assertThat(data.path("payment").path("status").asText()).isEqualTo("SUCCESS");
        assertThat(data.path("paidAmount").asLong()).isEqualTo(500);
        return data;
    }

    private void assertApiFacts(Fixture fixture) {
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT send_started_at FROM payment_attempt WHERE order_id=?", LocalDateTime.class, fixture.orderId()))
            .isEqualTo(LocalDateTime.ofInstant(FIXED_PAYMENT_TIME, ZoneOffset.UTC));
        assertThat(jdbc.queryForList("SELECT reservation_status FROM order_item WHERE order_id=?", String.class, fixture.orderId()))
            .containsExactly("CONSUMED", "CONSUMED");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.firstProductId())).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.secondProductId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(1000L);
    }

    private void assertPg(Fixture fixture) {
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
        assertThat(PG.requests()).filteredOn(request -> request.method().equals("POST")).singleElement().satisfies(request -> {
            assertThat(request.rawPath()).isEqualTo("/api/v1/payments");
            assertThat(request.account()).isEqualTo("135135");
            assertThat(request.orderId()).isEqualTo("LP-ORD-%010d".formatted(fixture.orderId()));
            assertThat(request.amount()).isEqualTo(500L);
            assertThat(request.cardMatchesExpected()).isTrue();
        });
    }

    private ConsumerRecord<String, String> onlyPublishedRecord() {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (records.isEmpty() && System.nanoTime() < deadline) {
            streamer.assertAlive();
            inspector.poll(Duration.ofMillis(100)).forEach(records::add);
        }
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    private void assertWire(ConsumerRecord<String, String> record, Fixture fixture, Instant confirmedAt) throws Exception {
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo(Long.toString(fixture.orderId()));
        JsonNode event = mapper.readTree(record.value());
        assertThat(event.properties()).extracting(Map.Entry::getKey)
            .containsExactlyInAnyOrder("eventType", "schemaVersion", "eventId", "orderId", "confirmedAt", "items");
        assertThat(event.path("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(event.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(event.path("orderId").asLong()).isEqualTo(fixture.orderId());
        assertThat(event.path("eventId").asText()).isEqualTo("order-confirmed:v1:" + fixture.orderId());
        assertThat(Instant.parse(event.path("confirmedAt").asText())).isEqualTo(confirmedAt);
        assertThat(event.path("items")).hasSize(2);
        assertThat(event.path("items").get(0).path("productId").asLong()).isEqualTo(fixture.firstProductId());
        assertThat(event.path("items").get(0).path("quantity").asInt()).isEqualTo(3);
        assertThat(event.path("items").get(1).path("productId").asLong()).isEqualTo(fixture.secondProductId());
        assertThat(event.path("items").get(1).path("quantity").asInt()).isEqualTo(2);
        assertThat(record.value()).doesNotContain("card", "fingerprint", "providerOrderId", "transactionKey", "userId", CARD);
    }

    private void assertActivity(Fixture fixture, Instant confirmedAt) throws Exception {
        ActivitySnapshot saved = activitySnapshot();
        assertThat(saved.confirmations()).hasSize(1);
        assertThat(saved.confirmations().getFirst()).containsEntry("order_id", fixture.orderId())
            .containsEntry("event_id", "order-confirmed:v1:" + fixture.orderId())
            .containsEntry("confirmed_at_second", confirmedAt.getEpochSecond()).containsEntry("confirmed_at_nano", confirmedAt.getNano());
        assertThat(saved.activities()).hasSize(2);
        assertThat(saved.activities()).extracting(row -> row.get("product_id")).containsExactly(fixture.firstProductId(), fixture.secondProductId());
        assertThat(saved.activities()).extracting(row -> row.get("quantity")).containsExactly(3, 2);
        for (var item : saved.activities()) {
            assertThat(item).containsEntry("order_id", fixture.orderId())
                .containsEntry("confirmed_at_second", confirmedAt.getEpochSecond()).containsEntry("confirmed_at_nano", confirmedAt.getNano());
        }
    }

    private ActivitySnapshot activitySnapshot() throws Exception {
        try (var connection = STREAMER_DB.createConnection("")) {
            var observer = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            return new ActivitySnapshot(observer.queryForList("SELECT * FROM processed_order_confirmation ORDER BY id"),
                observer.queryForList("SELECT * FROM product_purchase_activity ORDER BY product_id"));
        }
    }

    private long committed(ConsumerRecord<String, String> record) throws Exception {
        var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        var offset = offsets.get(new TopicPartition(record.topic(), record.partition()));
        return offset == null ? -1 : offset.offset();
    }

    private static AdminClient newAdmin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000, AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
    }

    private static LocalTestPg createPg() {
        try {
            return new LocalTestPg();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private record Fixture(long orderId, long firstProductId, long secondProductId, UUID key) { }
    private record ActivitySnapshot(List<Map<String, Object>> confirmations, List<Map<String, Object>> activities) { }
    private record ApiSnapshot(Map<String, Object> order, Map<String, Object> attempt, List<Map<String, Object>> items,
                               List<Map<String, Object>> products, long points) { }
    private record CommittedObservation(long writerConnection, long readerConnection, Instant confirmedAt, ApiSnapshot facts) { }
}
