package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.outbox.OutboxDeliveryTransactions;
import com.loopers.application.outbox.OutboxRelay;
import com.loopers.application.outbox.OutboxRelaySettings;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.event.DomainEventPublisher;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxReceipt;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.outbox.OutboxSender;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.support.event.W6DirectPublicationTestConfiguration;
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
import org.springframework.context.annotation.Import;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Preserve actual W6 facts, then backfill and deliver in the same owned DB/broker/PG execution. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"commerce.outbox-relay.enabled=false", "commerce.order-events.enabled=true"})
@Import(W6DirectPublicationTestConfiguration.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(120)
class W6OutboxBackfillEndToEndTest {
    private static final Instant FIXED_TIME = Instant.parse("2026-11-04T03:04:05.123456Z");
    private static final Instant RECOVERY_TIME = FIXED_TIME.plus(Duration.ofHours(1));
    private static final String CARD = "0000-0000-0000-0000";
    private static final String TOPIC = "w6-backfill-events-" + UUID.randomUUID();
    private static final String GROUP = "w6-backfill-activity-" + UUID.randomUUID();
    private static final List<TopicPartition> PARTITIONS = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
    private static final LocalTestPg PG = createPg();
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @Container
    private static final MySQLContainer<?> STREAMER_DB = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w6_backfill_streamer");

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
    @Autowired private OutboxDeliveryTransactions delivery;
    @Autowired private OrderOutboxBackfillService backfill;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private DomainEventPublisher publisher;
    @MockitoSpyBean private OrderPaymentStockService stockService;
    @MockitoSpyBean private OrderPaymentRecoveryService paymentRecovery;
    @MockitoSpyBean private OutboxSender sender;
    @TestBean(name = "outboxClock", methodName = "fixedRecoveryClock")
    private Clock outboxClock;
    private final List<OutboxReceipt> receipts = new CopyOnWriteArrayList<>();

    private AdminClient admin;
    private KafkaConsumer<String, String> inspector;
    private OwnedStreamerProcess streamer;

    static Clock fixedOrderPaymentClock() {
        return Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
    }

    static Clock fixedRecoveryClock() {
        return Clock.fixed(RECOVERY_TIME, ZoneOffset.UTC);
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
            ConsumerConfig.GROUP_ID_CONFIG, "w6-backfill-inspector-" + UUID.randomUUID(),
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
    void sameExecutionBackfillsMissingDeliveryAndPreservesAlreadyConsumedFacts(CapturedOutput output) throws Exception {
        startStreamer();
        assertThat(activitySnapshot().confirmations()).isEmpty();
        assertThat(activitySnapshot().activities()).isEmpty();
        assertThat(totalBrokerRecords()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
            + "AND table_name='product_purchase_activity'", Integer.class)).isZero();

        Fixture delivered = draftThroughHttp();
        PG.nextCreateStatus("SUCCESS");
        paymentSuccess(confirm(delivered));
        assertApiFacts(delivered);
        ConsumerRecord<String, String> original = onlyPublishedRecord();
        assertWire(original, delivered, FIXED_TIME);
        awaitConsumer(original, delivered, "APPLIED");
        assertActivity(delivered, FIXED_TIME);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getAll().lines())
            .anyMatch(line -> line.contains("domain_event_published") && line.contains("topic=" + TOPIC)
                && line.contains("partition=" + original.partition()) && line.contains("offset=" + original.offset())));
        ActivitySnapshot originallyConsumed = activitySnapshot();
        Map<TopicPartition, Long> originalEnds = inspector.endOffsets(PARTITIONS, Duration.ofSeconds(5));

        Fixture missing = prepareMissingW6Order(output);
        assertThat(activitySnapshot()).isEqualTo(originallyConsumed);
        assertThat(inspector.poll(Duration.ofMillis(200))).isEmpty();
        assertThat(inspector.endOffsets(PARTITIONS, Duration.ofSeconds(5))).isEqualTo(originalEnds);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message", Long.class)).isZero();
        assertThat(PG.createCalls()).isEqualTo(2);
        assertThat(PG.transactionCount()).isEqualTo(2);
        verify(publisher, times(2)).publish(any(OrderConfirmedEvent.class));
        List<ApiSnapshot> preparedBusiness = List.of(apiSnapshot(delivered), apiSnapshot(missing));
        PgSnapshot preparedPg = pgSnapshot();
        printPreparedSourceFacts("already_consumed", delivered);
        printPreparedSourceFacts("missing_delivery", missing);
        System.out.printf("w6_backfill_prepared topic=%s group=%s deliveredOrderId=%d missingOrderId=%d "
                + "confirmedAt=%s pgCreates=%d pgLookups=%d pgTransactions=%d outboxRows=0 brokerRecords=1 "
                + "completionRows=1 activityRows=2%n",
            TOPIC, GROUP, delivered.orderId(), missing.orderId(), FIXED_TIME,
            preparedPg.creates(), preparedPg.lookups(), preparedPg.transactions());

        // Test-only stop of W6 direct publication. W7 recovery is never allowed to reconfirm or publish directly.
        // No context/database/topic reset occurs, and no W7 Outbox is deleted to fabricate historical absence.
        doAnswer(invocation -> { throw new AssertionError("Direct publication is stopped during backfill"); })
            .when(publisher).publish(any(DomainEvent.class));
        clearInvocations(stockService, paymentRecovery);
        captureRealBrokerReceipts();

        var deliveredCreated = backfill.backfill(delivered.orderId());
        var missingCreated = backfill.backfill(missing.orderId());
        assertThat(deliveredCreated.outcome()).isEqualTo(OrderOutboxBackfillService.Outcome.CREATED);
        assertThat(missingCreated.outcome()).isEqualTo(OrderOutboxBackfillService.Outcome.CREATED);
        assertThat(deliveredCreated.eventId()).isEqualTo(eventId(delivered));
        assertThat(missingCreated.eventId()).isEqualTo(eventId(missing));
        long deliveredOutbox = deliveredCreated.outboxId();
        long missingOutbox = missingCreated.outboxId();
        assertPending(deliveredOutbox, delivered);
        assertPending(missingOutbox, missing);
        assertThat(mapper.readTree((String) immutableOutbox(deliveredOutbox).get("payload")))
            .isEqualTo(mapper.readTree(original.value()));
        Map<String, Object> deliveredFact = immutableOutbox(deliveredOutbox);
        Map<String, Object> missingFact = immutableOutbox(missingOutbox);
        assertRecoveryHasNoBusinessEffects(delivered, missing, preparedBusiness, preparedPg);
        assertThat(activitySnapshot()).isEqualTo(originallyConsumed);
        assertThat(totalBrokerRecords()).isEqualTo(1); // Backfill records facts; it does not send inline.

        assertThat(newRelay(RECOVERY_TIME).runOnce()).isEqualTo(new OutboxRelay.Result(2, 2, false));
        List<ConsumerRecord<String, String>> recovered = publishedRecords(2);
        ConsumerRecord<String, String> deliveredAgain = recordFor(recovered, delivered);
        ConsumerRecord<String, String> missingFirst = recordFor(recovered, missing);
        assertWire(deliveredAgain, delivered, FIXED_TIME);
        assertWire(missingFirst, missing, FIXED_TIME);
        assertThat(deliveredAgain.partition()).isEqualTo(original.partition());
        assertThat(deliveredAgain.offset()).isEqualTo(original.offset() + 1);
        assertThat(deliveredAgain.key()).isEqualTo(original.key());
        assertThat(mapper.readTree(deliveredAgain.value())).isEqualTo(mapper.readTree(original.value()));
        assertThat(deliveredAgain.value()).isEqualTo(deliveredFact.get("payload"));
        assertThat(missingFirst.value()).isEqualTo(missingFact.get("payload"));
        assertThat(receipts).containsExactlyInAnyOrder(receipt(deliveredAgain), receipt(missingFirst));
        awaitConsumer(deliveredAgain, delivered, "DUPLICATE");
        awaitConsumer(missingFirst, missing, "APPLIED");
        assertActivity(delivered, FIXED_TIME);
        assertActivity(missing, FIXED_TIME);
        ActivitySnapshot afterRecovery = activitySnapshot();
        assertThat(forOrder(afterRecovery, delivered)).isEqualTo(originallyConsumed);
        assertThat(afterRecovery.confirmations()).hasSize(2);
        assertThat(afterRecovery.activities()).hasSize(4);
        assertPublished(deliveredOutbox, 1);
        assertPublished(missingOutbox, 1);
        assertThat(immutableOutbox(deliveredOutbox)).isEqualTo(deliveredFact);
        assertThat(immutableOutbox(missingOutbox)).isEqualTo(missingFact);
        assertThat(totalBrokerRecords()).isEqualTo(3);
        assertRecoveryHasNoBusinessEffects(delivered, missing, preparedBusiness, preparedPg);

        List<Map<String, Object>> savedOutboxes = jdbc.queryForList("SELECT * FROM outbox_message ORDER BY id");
        assertThat(savedOutboxes).hasSize(2);
        Map<TopicPartition, Long> recoveredEnds = inspector.endOffsets(PARTITIONS, Duration.ofSeconds(5));
        var completedOffsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        assertThat(backfill.backfill(delivered.orderId())).isEqualTo(new OrderOutboxBackfillService.Result(
            eventId(delivered), deliveredOutbox, OrderOutboxBackfillService.Outcome.ALREADY_PRESENT));
        assertThat(backfill.backfill(missing.orderId())).isEqualTo(new OrderOutboxBackfillService.Result(
            eventId(missing), missingOutbox, OrderOutboxBackfillService.Outcome.ALREADY_PRESENT));
        assertThat(newRelay(RECOVERY_TIME.plusSeconds(60)).runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
        assertThat(jdbc.queryForList("SELECT * FROM outbox_message ORDER BY id")).isEqualTo(savedOutboxes);
        assertThat(activitySnapshot()).isEqualTo(afterRecovery);
        assertThat(inspector.poll(Duration.ofMillis(200))).isEmpty();
        assertThat(inspector.endOffsets(PARTITIONS, Duration.ofSeconds(5))).isEqualTo(recoveredEnds);
        assertThat(admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)).isEqualTo(completedOffsets);
        assertRecoveryHasNoBusinessEffects(delivered, missing, preparedBusiness, preparedPg);
        verify(publisher, times(2)).publish(any(OrderConfirmedEvent.class));
        verify(sender, times(2)).send(any(OutboxDeliveryAttempt.class));
        System.out.printf("w6_backfill_recovered deliveredOrderId=%d deliveredEventId=%s deliveredPartition=%d deliveredOffsets=%d,%d "
                + "missingOrderId=%d missingEventId=%s missingPartition=%d missingOffset=%d "
                + "outboxRows=2 completionRows=2 activityRows=4 quantitiesPerOrder=3,2 "
                + "pgCreateDelta=%d pgLookupDelta=%d transactions=%d businessUnchanged=true "
                + "alreadyConsumedPkFieldsUnchanged=true secondBackfillUnchanged=true%n",
            delivered.orderId(), eventId(delivered), original.partition(), original.offset(), deliveredAgain.offset(),
            missing.orderId(), eventId(missing), missingFirst.partition(), missingFirst.offset(),
            PG.createCalls() - preparedPg.creates(), PG.lookupCalls() - preparedPg.lookups(), PG.transactionCount());
    }

    private Fixture prepareMissingW6Order(CapturedOutput output) {
        Fixture missing = draftThroughHttp();
        AtomicReference<CommittedObservation> observed = new AtomicReference<>();
        doAnswer(invocation -> {
            // AFTER_COMMIT still binds the writer connection; another transaction must see committed facts.
            long writer = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            TransactionTemplate reader = new TransactionTemplate(transactionManager);
            reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            reader.setReadOnly(true);
            observed.set(reader.execute(status -> {
                assertApiFacts(missing);
                return new CommittedObservation(writer, jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class), apiSnapshot(missing));
            }));
            // Do not call the real adapter: there cannot be an outstanding Kafka future for this order.
            throw new IllegalStateException("test-only W6 pre-send gap");
        }).when(publisher).publish(argThat(event -> event instanceof OrderConfirmedEvent confirmed
            && confirmed.orderId() == missing.orderId()));
        PG.nextCreateStatus("SUCCESS");
        paymentSuccess(confirm(missing));
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().reader()).isNotEqualTo(observed.get().writer());
        assertApiFacts(missing);
        assertThat(apiSnapshot(missing)).usingRecursiveComparison().isEqualTo(observed.get().facts());
        assertThat(output.getAll().lines()).anyMatch(line -> line.contains("domain_event_dispatch_failed")
            && line.contains("eventId=" + eventId(missing) + " "));
        return missing;
    }

    private void assertPending(long id, Fixture fixture) {
        assertThat(outboxRow(id)).containsEntry("status", "PENDING").containsEntry("attempt_count", 0)
            .containsEntry("published_at", null).containsEntry("event_id", eventId(fixture));
        assertThat(outbox.findByEventId(eventId(fixture)).orElseThrow().getCreatedAt()).isEqualTo(RECOVERY_TIME);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message WHERE event_id=?", Long.class, eventId(fixture))).isEqualTo(1);
    }

    private void assertRecoveryHasNoBusinessEffects(Fixture delivered, Fixture missing, List<ApiSnapshot> prepared, PgSnapshot pgBefore) {
        assertThat(List.of(apiSnapshot(delivered), apiSnapshot(missing))).usingRecursiveComparison().isEqualTo(prepared);
        assertThat(pgSnapshot()).isEqualTo(pgBefore);
        verifyNoInteractions(stockService, paymentRecovery);
    }

    private ConsumerRecord<String, String> recordFor(List<ConsumerRecord<String, String>> records, Fixture fixture) {
        var selected = records.stream().filter(record -> record.key().equals(Long.toString(fixture.orderId()))).toList();
        assertThat(selected).hasSize(1);
        return selected.getFirst();
    }

    private ActivitySnapshot forOrder(ActivitySnapshot all, Fixture fixture) {
        return new ActivitySnapshot(all.confirmations().stream().filter(row -> row.get("order_id").equals(fixture.orderId())).toList(),
            all.activities().stream().filter(row -> row.get("order_id").equals(fixture.orderId())).toList());
    }

    private void startStreamer() throws Exception {
        streamer = OwnedStreamerProcess.start(STREAMER_DB.getJdbcUrl(), STREAMER_DB.getUsername(), STREAMER_DB.getPassword(),
            KAFKA.getBootstrapServers(), TOPIC, GROUP, Path.of("build", "reports", "w6-backfill-e2e", TOPIC, "streamer.log"));
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

    private void printPreparedSourceFacts(String scenario, Fixture fixture) {
        Map<String, Object> payment = jdbc.queryForMap(
            "SELECT provider_order_id,transaction_key,amount FROM payment_attempt WHERE order_id=?", fixture.orderId());
        String providerOrderId = (String) payment.get("provider_order_id");
        String transactionKey = (String) payment.get("transaction_key");
        assertThat(providerOrderId).isNotBlank();
        assertThat(transactionKey).isNotBlank();
        List<LocalTestPg.CapturedRequest> creates = PG.requests().stream()
            .filter(request -> request.method().equals("POST") && providerOrderId.equals(request.orderId())).toList();
        assertThat(creates).hasSize(1);
        assertThat(creates.getFirst().amount()).isEqualTo(payment.get("amount"));
        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT product_id,quantity FROM order_item WHERE order_id=? ORDER BY product_id", fixture.orderId());
        assertThat(items).extracting(row -> row.get("product_id"))
            .containsExactly(fixture.firstProductId(), fixture.secondProductId());
        assertThat(items).extracting(row -> row.get("quantity")).containsExactly(3, 2);
        Instant confirmedAt = jdbc.queryForObject("SELECT confirmed_at FROM `order` WHERE id=?", LocalDateTime.class, fixture.orderId())
            .toInstant(ZoneOffset.UTC);
        assertThat(confirmedAt).isEqualTo(FIXED_TIME);
        // Only persisted event/payment identities and item quantities; never print the full payment row or PG request.
        System.out.printf("w6_backfill_source scenario=%s orderId=%d eventId=%s providerOrderId=%s transactionKey=%s "
                + "amount=%s confirmedAt=%s productQuantities=%s:%s,%s:%s%n",
            scenario, fixture.orderId(), eventId(fixture), providerOrderId, transactionKey, payment.get("amount"), confirmedAt,
            items.get(0).get("product_id"), items.get(0).get("quantity"), items.get(1).get("product_id"), items.get(1).get("quantity"));
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message WHERE event_id=?", Long.class, eventId(fixture))).isZero();
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
        return publishedRecords(1).getFirst();
    }

    private List<ConsumerRecord<String, String>> publishedRecords(int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (records.size() < expected && System.nanoTime() < deadline) {
            streamer.assertAlive();
            inspector.poll(Duration.ofMillis(100)).forEach(records::add);
        }
        assertThat(records).hasSize(expected);
        return records;
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

    private record CommittedObservation(long writer, long reader, ApiSnapshot facts) { }
    private record Fixture(long orderId, long firstProductId, long secondProductId, UUID key) { }
    private record ActivitySnapshot(List<Map<String, Object>> confirmations, List<Map<String, Object>> activities) { }
    private record ApiSnapshot(Map<String, Object> order, Map<String, Object> attempt, List<Map<String, Object>> items,
                               List<Map<String, Object>> products, long points) { }
    private record PgSnapshot(int creates, int lookups, int transactions, List<LocalTestPg.CapturedRequest> requests) { }
}
