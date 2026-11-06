package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.outbox.OutboxDeliveryTransactions;
import com.loopers.application.outbox.OutboxRelay;
import com.loopers.application.outbox.OutboxRelaySettings;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxReceipt;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.outbox.OutboxSender;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** W7 controlled stop boundaries with real HTTP/PG/Outbox/Kafka and a separate production streamer JVM/DB. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"commerce.outbox-relay.enabled=false", "commerce.order-events.enabled=false"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(120)
class PgOrderOutboxRecoveryEndToEndTest {
    private static final Instant FIXED_TIME = Instant.parse("2026-11-04T03:04:05.123456Z");
    private static final String CARD = "0000-0000-0000-0000";
    private static final String TOPIC = "w7-outbox-restart-" + UUID.randomUUID();
    private static final String GROUP = "w7-outbox-activity-" + UUID.randomUUID();
    private static final List<TopicPartition> PARTITIONS = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
    private static final LocalTestPg PG = createPg();
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @Container
    private static final MySQLContainer<?> STREAMER_DB = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w7_streamer_restart");

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
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxRelaySettings settings;
    @Autowired private OutboxRelay initialRelay;
    @MockitoSpyBean private OutboxDeliveryTransactions delivery;
    @MockitoSpyBean private OutboxSender sender;
    @TestBean(name = "outboxClock", methodName = "fixedOrderPaymentClock")
    private Clock outboxClock;
    private final List<OutboxReceipt> receipts = new CopyOnWriteArrayList<>();

    private AdminClient admin;
    private KafkaConsumer<String, String> inspector;
    private OwnedStreamerProcess streamer;

    static Clock fixedOrderPaymentClock() {
        return Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
    }

    @DynamicPropertySource
    static void isolatedExternalEndpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.order-events.enabled", () -> false);
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
            ConsumerConfig.GROUP_ID_CONFIG, "w7-restart-inspector-" + UUID.randomUUID(),
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
    void bothStopBoundariesResumeTheSameFactsAndConsumerAppliesEachOrderOnce(CapturedOutput output) throws Exception {
        startStreamer();
        captureRealBrokerReceipts();
        assertThat(activitySnapshot().confirmations()).isEmpty();
        assertThat(activitySnapshot().activities()).isEmpty();
        assertThat(totalBrokerRecords()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
            + "AND table_name='product_purchase_activity'", Integer.class)).isZero();

        recoverCommittedOrderBeforeAnySend();
        recoverBrokerAcknowledgementBeforePublishedWrite();

        assertThat(totalBrokerRecords()).isEqualTo(3);
        assertThat(activitySnapshot().confirmations()).hasSize(2);
        assertThat(activitySnapshot().activities()).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message WHERE status='PUBLISHED'", Long.class)).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(2);
        assertThat(PG.transactionCount()).isEqualTo(2);
        verify(sender, times(3)).send(any(OutboxDeliveryAttempt.class));
        assertThat(output.getAll()).contains("outbox_state_write_failed");
    }

    private void recoverCommittedOrderBeforeAnySend() throws Exception {
        Fixture fixture = draftThroughHttp();
        PG.nextCreateStatus("SUCCESS");
        paymentSuccess(confirm(fixture));
        assertApiFacts(fixture);
        long id = outbox.findByEventId(eventId(fixture)).orElseThrow().getId();
        Map<String, Object> fact = immutableOutbox(id);
        ApiSnapshot before = apiSnapshot(fixture);
        PgSnapshot pgBefore = pgSnapshot();
        var pending = outboxRow(id);
        assertThat(pending).containsEntry("status", "PENDING").containsEntry("attempt_count", 0)
            .containsEntry("published_at", null);
        assertThat(totalBrokerRecords()).isZero();
        assertThat(inspector.poll(Duration.ofMillis(200))).isEmpty();
        assertThat(activitySnapshot().confirmations()).isEmpty();
        assertThat(activitySnapshot().activities()).isEmpty();

        // Controlled stop after the HTTP confirmation committed, before any relay round.
        // No database reset or outcome rewriting occurs; a fresh runner reads the saved row.
        var restarted = newRelay(FIXED_TIME);
        assertThat(restarted).isNotSameAs(initialRelay);
        assertThat(restarted.runOnce()).isEqualTo(new OutboxRelay.Result(1, 1, false));
        ConsumerRecord<String, String> record = onlyPublishedRecord();
        assertWire(record, fixture, FIXED_TIME);
        assertThat(record.value()).isEqualTo(fact.get("payload"));
        assertThat(receipts).containsExactly(receipt(record));
        awaitConsumer(record, fixture, "APPLIED");
        assertActivity(fixture, FIXED_TIME);
        assertPublished(id, 1);
        assertThat(immutableOutbox(id)).isEqualTo(fact);
        assertThat(apiSnapshot(fixture)).usingRecursiveComparison().isEqualTo(before);
        assertThat(pgSnapshot()).isEqualTo(pgBefore);
        assertThat(totalBrokerRecords()).isEqualTo(1);
        ActivitySnapshot activities = activitySnapshot();
        Map<String, Object> published = outboxRow(id);
        assertThat(newRelay(FIXED_TIME.plusSeconds(60)).runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
        assertThat(outboxRow(id)).isEqualTo(published);
        assertThat(activitySnapshot()).isEqualTo(activities);
        assertThat(totalBrokerRecords()).isEqualTo(1);
        System.out.printf("w7_before_send_resume orderId=%d eventId=%s confirmedAt=%s topic=%s partition=%d offset=%d "
                + "outboxRows=1 attempts=1 brokerRecords=1 completionRows=1 activityRows=2 quantities=3,2 "
                + "pgCreateDelta=0 pgLookupDelta=0 businessUnchanged=true%n",
            fixture.orderId(), eventId(fixture), FIXED_TIME, TOPIC, record.partition(), record.offset());
    }

    private void recoverBrokerAcknowledgementBeforePublishedWrite() throws Exception {
        Fixture fixture = draftThroughHttp();
        PG.nextCreateStatus("SUCCESS");
        paymentSuccess(confirm(fixture));
        assertApiFacts(fixture);
        long id = outbox.findByEventId(eventId(fixture)).orElseThrow().getId();
        Map<String, Object> fact = immutableOutbox(id);
        ApiSnapshot before = apiSnapshot(fixture);
        PgSnapshot pgBefore = pgSnapshot();
        ActivitySnapshot priorOrderActivities = activitySnapshot();
        AtomicBoolean interruptedAtBoundary = new AtomicBoolean();
        doAnswer(invocation -> {
            assertThat(receipts).hasSize(2); // Previous case + this real completed producer future.
            assertThat(receipts.getLast().topic()).isEqualTo(TOPIC);
            assertThat(outboxRow(id)).containsEntry("status", "PENDING").containsEntry("attempt_count", 1)
                .containsEntry("published_at", null);
            interruptedAtBoundary.set(true);
            // Before the real method body: no PUBLISHED UPDATE executes. This is not an OS kill.
            throw new IllegalStateException("test-only stop after broker ACK before PUBLISHED");
        }).when(delivery).markPublished(eq(id), eq(1), any(Instant.class));

        var interrupted = newRelay(FIXED_TIME);
        assertThat(interrupted.runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));
        assertThat(interruptedAtBoundary).isTrue();
        ConsumerRecord<String, String> first = onlyPublishedRecord();
        assertWire(first, fixture, FIXED_TIME);
        assertThat(receipts.getLast()).isEqualTo(receipt(first));
        assertThat(totalBrokerRecords()).isEqualTo(2);
        assertThat(immutableOutbox(id)).isEqualTo(fact);
        var afterStop = outboxRow(id);
        assertThat(afterStop).containsEntry("status", "PENDING").containsEntry("attempt_count", 1)
            .containsEntry("published_at", null);
        assertThat(apiSnapshot(fixture)).usingRecursiveComparison().isEqualTo(before);
        assertThat(pgSnapshot()).isEqualTo(pgBefore);
        awaitConsumer(first, fixture, "APPLIED");
        assertActivity(fixture, FIXED_TIME);
        ActivitySnapshot once = activitySnapshot();
        assertThat(once.confirmations()).containsAll(priorOrderActivities.confirmations());
        assertThat(once.activities()).containsAll(priorOrderActivities.activities());

        // Advance only the injected test Clock to the persisted eligibility boundary.
        // Same database, broker, group, PG transactions, eventId and immutable payload remain alive.
        Instant retryAt = outbox.findByEventId(eventId(fixture)).orElseThrow().getNextAttemptAt();
        assertThat(retryAt).isEqualTo(FIXED_TIME.plus(settings.sendBudget()).plus(settings.retryInitial()));
        assertThat(newRelay(retryAt.minusNanos(1)).runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
        assertThat(outboxRow(id)).isEqualTo(afterStop);
        var restarted = newRelay(retryAt);
        assertThat(restarted).isNotSameAs(interrupted);
        assertThat(restarted.runOnce()).isEqualTo(new OutboxRelay.Result(1, 1, false));
        ConsumerRecord<String, String> second = onlyPublishedRecord();
        assertWire(second, fixture, FIXED_TIME);
        assertThat(second.topic()).isEqualTo(first.topic());
        assertThat(second.partition()).isEqualTo(first.partition());
        assertThat(second.offset()).isEqualTo(first.offset() + 1);
        assertThat(second.key()).isEqualTo(first.key());
        assertThat(second.value()).isEqualTo(first.value()).isEqualTo(fact.get("payload"));
        assertThat(receipts.getLast()).isEqualTo(receipt(second));
        awaitConsumer(second, fixture, "DUPLICATE");
        assertThat(streamer.log()).contains(consumerLine(first, fixture, "APPLIED"), consumerLine(second, fixture, "DUPLICATE"));
        assertThat(activitySnapshot()).isEqualTo(once); // Includes PKs and all fields for both orders.
        assertPublished(id, 2);
        assertThat(immutableOutbox(id)).isEqualTo(fact);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message WHERE event_id=?", Long.class, eventId(fixture))).isEqualTo(1);
        assertThat(apiSnapshot(fixture)).usingRecursiveComparison().isEqualTo(before);
        assertThat(pgSnapshot()).isEqualTo(pgBefore);
        assertThat(totalBrokerRecords()).isEqualTo(3);
        Map<String, Object> published = outboxRow(id);
        assertThat(newRelay(retryAt.plusSeconds(60)).runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
        assertThat(outboxRow(id)).isEqualTo(published);
        assertThat(inspector.poll(Duration.ofMillis(200))).isEmpty();
        assertThat(totalBrokerRecords()).isEqualTo(3);
        assertThat(activitySnapshot()).isEqualTo(once);
        System.out.printf("w7_after_ack_resume orderId=%d eventId=%s confirmedAt=%s topic=%s partition=%d offsets=%d,%d "
                + "outboxRows=1 attempts=2 brokerRecords=2 consumerReceipts=2 completionRows=1 activityRows=2 quantities=3,2 "
                + "committedOffset=%d pgCreateDelta=0 pgLookupDelta=0 businessUnchanged=true activityPkFieldsUnchanged=true%n",
            fixture.orderId(), eventId(fixture), FIXED_TIME, TOPIC, first.partition(), first.offset(), second.offset(), committed(second));
    }

    private void startStreamer() throws Exception {
        streamer = OwnedStreamerProcess.start(STREAMER_DB.getJdbcUrl(), STREAMER_DB.getUsername(), STREAMER_DB.getPassword(),
            KAFKA.getBootstrapServers(), TOPIC, GROUP, Path.of("build", "reports", "w7-outbox-e2e", TOPIC, "streamer.log"));
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            streamer.assertAlive();
            assertThat(admin.listConsumerGroups().valid().get(5, TimeUnit.SECONDS))
                .extracting(listing -> listing.groupId()).contains(GROUP);
            var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(5, TimeUnit.SECONDS).get(GROUP);
            assertThat(group.state()).isEqualTo(ConsumerGroupState.STABLE);
            assertThat(group.members()).hasSize(1);
            assertThat(group.members().iterator().next().assignment().topicPartitions()).containsExactlyInAnyOrderElementsOf(PARTITIONS);
        });
    }

    private void captureRealBrokerReceipts() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            @SuppressWarnings("unchecked")
            var actual = (CompletableFuture<OutboxReceipt>) invocation.callRealMethod();
            return actual.thenApply(receipt -> { receipts.add(receipt); return receipt; });
        }).when(sender).send(any(OutboxDeliveryAttempt.class));
    }

    private OutboxRelay newRelay(Instant now) {
        return new OutboxRelay(delivery, sender, Clock.fixed(now, ZoneOffset.UTC), settings, System::nanoTime);
    }

    private void awaitConsumer(ConsumerRecord<String, String> record, Fixture fixture, String result) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            streamer.assertAlive();
            assertThat(committed(record)).isEqualTo(record.offset() + 1);
            assertThat(streamer.log()).contains(consumerLine(record, fixture, result));
        });
    }

    private String consumerLine(ConsumerRecord<String, String> record, Fixture fixture, String result) {
        return "purchase_activity_completed topic=" + TOPIC + " partition=" + record.partition()
            + " offset=" + record.offset() + " orderId=" + fixture.orderId() + " result=" + result;
    }

    private String eventId(Fixture fixture) {
        return "order-confirmed:v1:" + fixture.orderId();
    }

    private OutboxReceipt receipt(ConsumerRecord<String, String> record) {
        return new OutboxReceipt(record.topic(), record.partition(), record.offset());
    }

    private long totalBrokerRecords() {
        return inspector.endOffsets(PARTITIONS, Duration.ofSeconds(5)).values().stream().mapToLong(Long::longValue).sum();
    }

    private Map<String, Object> outboxRow(long id) {
        return jdbc.queryForMap("SELECT * FROM outbox_message WHERE id=?", id);
    }

    private Map<String, Object> immutableOutbox(long id) {
        return jdbc.queryForMap("SELECT id,event_id,aggregate_type,aggregate_id,event_type,schema_version,topic,message_key,payload,created_at "
            + "FROM outbox_message WHERE id=?", id);
    }

    private void assertPublished(long id, int attempts) {
        assertThat(outboxRow(id)).containsEntry("status", "PUBLISHED").containsEntry("attempt_count", attempts);
        assertThat(outboxRow(id).get("published_at")).isNotNull();
    }

    private PgSnapshot pgSnapshot() {
        return new PgSnapshot(PG.createCalls(), PG.lookupCalls(), PG.transactionCount(), PG.requests());
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message WHERE event_id=?", Long.class, eventId(fixture))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, fixture.orderId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?", String.class, fixture.orderId())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT send_started_at FROM payment_attempt WHERE order_id=?", LocalDateTime.class, fixture.orderId()))
            .isEqualTo(LocalDateTime.ofInstant(FIXED_TIME, ZoneOffset.UTC));
        assertThat(jdbc.queryForList("SELECT reservation_status FROM order_item WHERE order_id=?", String.class, fixture.orderId()))
            .containsExactly("CONSUMED", "CONSUMED");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.firstProductId())).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, fixture.secondProductId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(1000L);
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
        assertThat(record.headers().toArray()).isEmpty();
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
        ActivitySnapshot all = activitySnapshot();
        ActivitySnapshot saved = new ActivitySnapshot(all.confirmations().stream()
            .filter(row -> row.get("order_id").equals(fixture.orderId())).toList(),
            all.activities().stream().filter(row -> row.get("order_id").equals(fixture.orderId())).toList());
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
    private record PgSnapshot(int creates, int lookups, int transactions, List<LocalTestPg.CapturedRequest> requests) { }
}
