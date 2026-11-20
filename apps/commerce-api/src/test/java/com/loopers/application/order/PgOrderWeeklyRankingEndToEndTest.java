package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.outbox.OutboxRelay;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxReceipt;
import com.loopers.domain.outbox.OutboxSender;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.support.kafka.OwnedStreamerProcess;
import com.loopers.support.ranking.OwnedRankingBatchProcess;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Production HTTP/PG/relay/consumer/job/query connected without sharing API and Batch classpaths. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"commerce.outbox-relay.enabled=false", "commerce.order-events.enabled=false"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(240)
class PgOrderWeeklyRankingEndToEndTest {
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 17);
    private static final Instant CONFIRMED_AT = Instant.parse("2026-08-20T12:34:56.123456Z");
    private static final String CARD = "0000-0000-0000-0000";
    private static final String RESULT_CHECKSUM = "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2";
    private static final String TOPIC = "w9-ranking-orders-" + UUID.randomUUID();
    private static final String GROUP = "w9-ranking-activity-" + UUID.randomUUID();
    private static final List<TopicPartition> PARTITIONS = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
    private static final LocalTestPg PG = newPg();
    @Container private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @Container private static final MySQLContainer<?> ACTIVITY_DB = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w9_activity_ranking_owned");
    private static HikariDataSource observerPool;
    private static JdbcTemplate activity;

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;
    @TestBean(name = "orderPaymentClock", methodName = "fixedClock") private Clock paymentClock;
    @TestBean(name = "outboxClock", methodName = "fixedClock") private Clock outboxClock;
    @Autowired private TestRestTemplate rest;
    @Autowired private BrandRepository brands;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private OutboxRelay relay;
    @Autowired private OutboxSender sender;
    private AdminClient admin;
    private KafkaConsumer<String, String> inspector;
    private OwnedStreamerProcess streamer;

    static Clock fixedClock() { return Clock.fixed(CONFIRMED_AT, ZoneOffset.UTC); }

    @DynamicPropertySource
    static void onlyIsolatedEndpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", KAFKA::getBootstrapServers);
        registry.add("commerce.test-pg.enabled", () -> true);
        registry.add("commerce.test-pg.base-url", () -> PG.baseUri().toString());
        registry.add("commerce.test-pg.callback-url", () -> PG.callbackUri().toString());
        registry.add("commerce.ranking.query.enabled", () -> true);
        registry.add("commerce.ranking.query.jdbc-url", ACTIVITY_DB::getJdbcUrl);
        registry.add("commerce.ranking.query.username", ACTIVITY_DB::getUsername);
        registry.add("commerce.ranking.query.password", ACTIVITY_DB::getPassword);
    }

    @BeforeAll
    static void prepareOwnedActivitySchemaOnce() throws Exception {
        observerPool = new HikariDataSource();
        observerPool.setJdbcUrl(ACTIVITY_DB.getJdbcUrl());
        observerPool.setUsername(ACTIVITY_DB.getUsername());
        observerPool.setPassword(ACTIVITY_DB.getPassword());
        observerPool.setMaximumPoolSize(3);
        observerPool.setMinimumIdle(0);
        observerPool.setConnectionTimeout(3000);
        observerPool.addDataSourceProperty("connectTimeout", "3000");
        observerPool.addDataSourceProperty("socketTimeout", "10000");
        new ResourceDatabasePopulator(new FileSystemResource(System.getProperty("w6.streamer.schema")),
            new ClassPathResource("fixtures/ranking/001-weekly-ranking-input.sql"),
            new ClassPathResource("fixtures/ranking/002-weekly-ranking-score.sql"),
            new ClassPathResource("fixtures/ranking/003-weekly-ranking-publication.sql")).execute(observerPool);
        activity = new JdbcTemplate(observerPool);
        activity.setQueryTimeout(5);
        try (AdminClient setup = newAdmin()) {
            setup.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void prepareHttpAndObservation() {
        PG.reset();
        initializer.initialize();
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        long brand = brands.save(new Brand("W9 실제 주문 브랜드")).getId();
        for (long product : List.of(7L, 8L, 9L)) {
            // Only catalog fixtures are seeded. All orders/payments/activities go through production HTTP and Kafka.
            jdbc.update("INSERT INTO product(id,brand_id,name,price,stock_quantity,created_at,updated_at)"
                + " VALUES(?,?,?,100,20,NOW(6),NOW(6))", product, brand, "상품 " + product);
        }
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        rest.getRestTemplate().setRequestFactory(requestFactory);
        admin = newAdmin();
        inspector = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "w9-inspector-" + UUID.randomUUID(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000));
        inspector.assign(PARTITIONS);
        inspector.seekToBeginning(PARTITIONS);
    }

    @AfterEach
    void closeOwnedProcessesBeforeCleaningApiFixture(CapturedOutput output) throws Exception {
        try {
            if (streamer != null) { streamer.close(); }
        } finally {
            try {
                if (inspector != null) { inspector.close(Duration.ofSeconds(5)); }
            } finally {
                try {
                    if (admin != null) { admin.close(Duration.ofSeconds(5)); }
                } finally {
                    PG.releaseResponses();
                    cleanup.truncateAllTables();
                }
            }
        }
        assertThat(output.getAll()).doesNotContain(CARD);
        if (streamer != null) { assertThat(streamer.log()).doesNotContain(CARD); }
    }

    @AfterAll
    static void closeOwnedSources() {
        try { PG.close(); } finally { if (observerPool != null) { observerPool.close(); } }
    }

    @Test
    void duplicateEventsDoNotIncreaseScoresAndLatePurchaseOnlyEntersANewRevision() throws Exception {
        startStreamer();
        assertThat(rankingResponse().getStatusCode().value()).isEqualTo(404);
        Order first = purchase("{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}", 300);
        Order second = purchase("{\"productId\":7,\"quantity\":3}", 300);
        Order third = purchase("{\"productId\":9,\"quantity\":5}", 500);
        assertThat(relay.runOnce()).isEqualTo(new OutboxRelay.Result(3, 3, false));
        List<ConsumerRecord<String, String>> initialRecords = records(3);
        for (var record : initialRecords) { consumed(record, "APPLIED"); }
        assertThat(activity.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class)).isEqualTo(4);
        String expectedInput = inputChecksum(first, second, third);

        OwnedRankingBatchProcess.initializeSchema(ACTIVITY_DB.getJdbcUrl(), ACTIVITY_DB.getUsername(), ACTIVITY_DB.getPassword());
        var normal = batch(1, null);
        assertThat(normal.status()).isEqualTo("COMPLETED");
        assertThat(normal.stepNames()).containsExactly("freezeWeeklyInput", "calculateWeeklyScores", "publishWeeklyRanking");
        assertThat(normal.inputChecksum()).isEqualTo(expectedInput);
        assertThat(normal.resultChecksum()).isEqualTo(RESULT_CHECKSUM);
        JsonNode originalHttp = published(1, 5);
        var originalInputs = frozenRows(1);
        var originalResults = resultRows(1);
        var sourceBeforeDuplicate = sourceRows();
        var confirmationsBeforeDuplicate = activity.queryForList("SELECT * FROM processed_order_confirmation ORDER BY id");
        var businessBeforeDuplicate = business();
        var pgBeforeDuplicate = pgFacts();

        // Explicit broker replay of the saved immutable event; never reset Outbox or reconfirm the order.
        var saved = jdbc.queryForMap("SELECT * FROM outbox_message WHERE event_id=?", first.eventId());
        OutboxReceipt replay = sender.send(new OutboxDeliveryAttempt(((Number) saved.get("id")).longValue(), first.eventId(),
            TOPIC, (String) saved.get("message_key"), (String) saved.get("payload"), 1)).get(12, TimeUnit.SECONDS);
        var duplicate = records(1).getFirst();
        var firstRecord = initialRecords.stream().filter(record -> record.key().equals(Long.toString(first.id()))).findFirst().orElseThrow();
        assertThat(duplicate.partition()).isEqualTo(firstRecord.partition());
        assertThat(duplicate.offset()).isGreaterThan(firstRecord.offset());
        assertThat(duplicate.value()).isEqualTo(firstRecord.value()).isEqualTo(saved.get("payload"));
        assertThat(replay).isEqualTo(new OutboxReceipt(TOPIC, duplicate.partition(), duplicate.offset()));
        consumed(duplicate, "DUPLICATE");
        assertThat(sourceRows()).isEqualTo(sourceBeforeDuplicate);
        assertThat(activity.queryForList("SELECT * FROM processed_order_confirmation ORDER BY id")).isEqualTo(confirmationsBeforeDuplicate);
        assertThat(published(1, 5)).isEqualTo(originalHttp);
        assertThat(pgFacts()).isEqualTo(pgBeforeDuplicate);
        assertThat(business()).usingRecursiveComparison().isEqualTo(businessBeforeDuplicate);

        // The purchase really succeeds in the original week, but its Outbox has not reached the activity DB yet.
        Order late = purchase("{\"productId\":7,\"quantity\":4}", 400);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_message WHERE event_id=?", String.class, late.eventId())).isEqualTo("PENDING");
        assertThat(sourceRows()).isEqualTo(sourceBeforeDuplicate);
        var stableBusiness = business();
        var stablePg = pgFacts();
        assertThat(stablePg.creates()).isEqualTo(4);
        assertThat(stablePg.transactions()).isEqualTo(4);

        var failed = batch(2, 9L);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureTypes()).anyMatch(type -> type.endsWith("$InjectedBeforeProductFailure"));
        assertThat(failed.readerPosition()).isEqualTo(2);
        assertThat(failed.visitedProducts()).containsExactly(7L, 8L, 9L);
        assertThat(failed.inputChecksum()).isEqualTo(expectedInput);
        assertThat(activity.queryForList("SELECT product_id,score FROM weekly_ranking_score WHERE snapshot_id=? ORDER BY product_id", id(2)))
            .containsExactly(Map.of("product_id", 7L, "score", 5L), Map.of("product_id", 8L, "score", 1L));
        assertThat(published(1, 5)).isEqualTo(originalHttp);
        var frozenBeforeLate = frozenRows(2);
        var frozenManifest = activity.queryForMap("SELECT frozen_at_second,frozen_at_nano,input_count,product_count,input_checksum"
            + " FROM weekly_ranking_snapshot WHERE snapshot_id=?", id(2));

        assertThat(relay.runOnce()).isEqualTo(new OutboxRelay.Result(1, 1, false));
        var lateRecord = records(1).getFirst();
        assertThat(lateRecord.key()).isEqualTo(Long.toString(late.id()));
        assertThat(Instant.parse(mapper.readTree(lateRecord.value()).path("confirmedAt").asText())).isEqualTo(CONFIRMED_AT);
        consumed(lateRecord, "APPLIED");
        assertThat(sourceRows()).hasSize(5);
        assertThat(activity.queryForMap("SELECT product_id,quantity,confirmed_at_second,confirmed_at_nano FROM product_purchase_activity WHERE order_id=?", late.id()))
            .containsEntry("product_id", 7L).containsEntry("quantity", 4)
            .containsEntry("confirmed_at_second", CONFIRMED_AT.getEpochSecond()).containsEntry("confirmed_at_nano", CONFIRMED_AT.getNano());
        assertThat(published(1, 5)).isEqualTo(originalHttp);

        var resumed = batch(2, null);
        assertThat(resumed.status()).isEqualTo("COMPLETED");
        assertThat(resumed.jobInstanceId()).isEqualTo(failed.jobInstanceId());
        assertThat(resumed.jobExecutionId()).isNotEqualTo(failed.jobExecutionId());
        assertThat(resumed.stepNames()).containsExactly("calculateWeeklyScores", "publishWeeklyRanking");
        assertThat(resumed.visitedProducts()).containsExactly(9L);
        assertThat(resumed.inputChecksum()).isEqualTo(expectedInput);
        assertThat(resumed.resultChecksum()).isEqualTo(normal.resultChecksum());
        assertThat(frozenRows(2)).isEqualTo(frozenBeforeLate);
        assertThat(activity.queryForMap("SELECT frozen_at_second,frozen_at_nano,input_count,product_count,input_checksum"
            + " FROM weekly_ranking_snapshot WHERE snapshot_id=?", id(2))).isEqualTo(frozenManifest);
        assertThat(resultRows(2)).isEqualTo(originalResults);
        published(2, 5);

        var revised = batch(3, null);
        assertThat(revised.status()).isEqualTo("COMPLETED");
        assertThat(revised.jobInstanceId()).isNotEqualTo(resumed.jobInstanceId());
        assertThat(revised.inputChecksum()).isEqualTo(inputChecksum(first, second, third, late)).isNotEqualTo(expectedInput);
        assertThat(revised.resultChecksum()).isEqualTo(sha256("7\t9\t1\n9\t5\t2\n8\t1\t3\n"));
        assertThat(frozenRows(3)).hasSize(5);
        published(3, 9);
        assertThat(frozenRows(1)).isEqualTo(originalInputs);
        assertThat(resultRows(1)).isEqualTo(originalResults);
        assertThat(frozenRows(2)).isEqualTo(frozenBeforeLate);
        assertThat(resultRows(2)).isEqualTo(originalResults);
        assertThat(pgFacts()).isEqualTo(stablePg);
        assertThat(business()).usingRecursiveComparison().isEqualTo(stableBusiness);
        assertThat(jdbc.queryForList("SELECT stock_quantity FROM product ORDER BY id", Integer.class)).containsExactly(11, 19, 15);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message WHERE status='PUBLISHED'", Long.class)).isEqualTo(4);
        assertThat(activity.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation", Long.class)).isEqualTo(4);
        assertThat(relay.runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
        assertThat(inspector.endOffsets(PARTITIONS).values().stream().mapToLong(Long::longValue).sum()).isEqualTo(5);

        System.out.printf("w9_full_chain topic=%s group=%s orderIds=%s confirmedAt=%s duplicatePartition=%d duplicateOffsets=%d,%d "
                + "sourceRows=4->5 frozenRows=4,4,5 score7=5,5,9 pgCreateDeltaAfterPreparation=0 businessUnchanged=true "
                + "normalInstance=%d failedInstance=%d failedExecution=%d resumedExecution=%d revisedInstance=%d "
                + "inputChecksum=%s revisedInputChecksum=%s resultChecksum=%s revisedResultChecksum=%s%n",
            TOPIC, GROUP, List.of(first.id(), second.id(), third.id(), late.id()), CONFIRMED_AT,
            duplicate.partition(), firstRecord.offset(), duplicate.offset(), normal.jobInstanceId(), failed.jobInstanceId(),
            failed.jobExecutionId(), resumed.jobExecutionId(), revised.jobInstanceId(), expectedInput,
            revised.inputChecksum(), RESULT_CHECKSUM, revised.resultChecksum());
    }

    private OwnedRankingBatchProcess.Result batch(int revision, Long failBeforeProduct) throws Exception {
        return OwnedRankingBatchProcess.run(ACTIVITY_DB.getJdbcUrl(), ACTIVITY_DB.getUsername(), ACTIVITY_DB.getPassword(),
            WEEK, revision, failBeforeProduct);
    }

    private JsonNode published(int revision, long scoreSeven) {
        var response = rankingResponse();
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        assertThat(response.getBody().at("/meta/result").asText()).isEqualTo("SUCCESS");
        var data = response.getBody().path("data");
        assertThat(data.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(data.path("snapshotId").asText()).isEqualTo(id(revision));
        assertThat(data.path("revision").asInt()).isEqualTo(revision);
        assertThat(data.path("periodStart").asText()).isEqualTo(WEEK.toString());
        assertThat(data.path("periodEnd").asText()).isEqualTo(WEEK.plusDays(7).toString());
        var manifest = activity.queryForMap("SELECT frozen_at_second,frozen_at_nano FROM weekly_ranking_snapshot WHERE snapshot_id=?", id(revision));
        assertThat(Instant.parse(data.path("frozenAt").asText())).isEqualTo(Instant.ofEpochSecond(
            ((Number) manifest.get("frozen_at_second")).longValue(), ((Number) manifest.get("frozen_at_nano")).intValue()));
        assertThat(data.path("items")).hasSize(3);
        assertItem(data.path("items").get(0), 7, scoreSeven, 1);
        assertItem(data.path("items").get(1), 9, 5, 2);
        assertItem(data.path("items").get(2), 8, 1, 3);
        return data;
    }

    private void assertItem(JsonNode item, long product, long score, long rank) {
        assertThat(item.path("productId").asLong()).isEqualTo(product);
        assertThat(item.path("productName").asText()).isEqualTo("상품 " + product);
        assertThat(item.path("score").asLong()).isEqualTo(score);
        assertThat(item.path("rank").asLong()).isEqualTo(rank);
    }

    private ResponseEntity<JsonNode> rankingResponse() {
        return rest.getForEntity("/api/v1/rankings/weekly?periodStart=" + WEEK + "&limit=3", JsonNode.class);
    }

    private Order purchase(String items, long expectedAmount) {
        var draft = post("/api/v1/orders", "{\"items\":[" + items + "]}");
        assertThat(draft.getStatusCode().value()).isEqualTo(201);
        long orderId = draft.getBody().at("/data/orderId").asLong();
        PG.nextCreateStatus("SUCCESS");
        var confirmed = post("/api/v1/orders/" + orderId + "/confirm", "{\"paymentAttemptKey\":\"" + UUID.randomUUID()
            + "\",\"cardType\":\"TEST\",\"cardNo\":\"" + CARD + "\"}");
        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        assertThat(confirmed.getBody().at("/data/status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.getBody().at("/data/paymentMethod").asText()).isEqualTo("PG");
        assertThat(confirmed.getBody().at("/data/payment/status").asText()).isEqualTo("SUCCESS");
        assertThat(confirmed.getBody().at("/data/paidAmount").asLong()).isEqualTo(expectedAmount);
        assertThat(jdbc.queryForList("SELECT reservation_status FROM order_item WHERE order_id=?", String.class, orderId))
            .isNotEmpty().containsOnly("CONSUMED");
        return new Order(orderId);
    }

    private ResponseEntity<JsonNode> post(String path, String body) {
        var headers = new HttpHeaders();
        headers.set("X-USER-ID", "alice");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void startStreamer() throws Exception {
        streamer = OwnedStreamerProcess.start(ACTIVITY_DB.getJdbcUrl(), ACTIVITY_DB.getUsername(), ACTIVITY_DB.getPassword(),
            KAFKA.getBootstrapServers(), TOPIC, GROUP, Path.of("build/reports/w9-full-chain", TOPIC, "streamer.log"));
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            streamer.assertAlive();
            var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(5, TimeUnit.SECONDS).get(GROUP);
            assertThat(group.state()).isEqualTo(ConsumerGroupState.STABLE);
            assertThat(group.members()).hasSize(1);
            assertThat(group.members().iterator().next().assignment().topicPartitions()).containsExactlyInAnyOrderElementsOf(PARTITIONS);
        });
    }

    private List<ConsumerRecord<String, String>> records(int count) {
        List<ConsumerRecord<String, String>> result = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (result.size() < count && System.nanoTime() < deadline) {
            streamer.assertAlive();
            inspector.poll(Duration.ofMillis(100)).forEach(result::add);
        }
        assertThat(result).hasSize(count);
        return result;
    }

    private void consumed(ConsumerRecord<String, String> record, String outcome) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            streamer.assertAlive();
            var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            assertThat(offsets).containsKey(new TopicPartition(TOPIC, record.partition()));
            assertThat(offsets.get(new TopicPartition(TOPIC, record.partition())).offset()).isGreaterThanOrEqualTo(record.offset() + 1);
            assertThat(streamer.log()).contains("purchase_activity_completed topic=" + TOPIC + " partition=" + record.partition()
                + " offset=" + record.offset() + " orderId=" + record.key() + " result=" + outcome);
        });
    }

    private List<Map<String, Object>> sourceRows() {
        return activity.queryForList("SELECT * FROM product_purchase_activity ORDER BY order_id,product_id");
    }

    private List<Map<String, Object>> frozenRows(int revision) {
        return activity.queryForList("SELECT * FROM weekly_ranking_input WHERE snapshot_id=? ORDER BY order_id,product_id", id(revision));
    }

    private List<Map<String, Object>> resultRows(int revision) {
        return activity.queryForList("SELECT product_id,score,ranking_position FROM weekly_ranking_result WHERE snapshot_id=? ORDER BY ranking_position", id(revision));
    }

    private List<Object> business() {
        return List.of(jdbc.queryForList("SELECT * FROM `order` ORDER BY id"), jdbc.queryForList("SELECT * FROM payment_attempt ORDER BY id"),
            jdbc.queryForList("SELECT * FROM order_item ORDER BY id"), jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class));
    }

    private PgFacts pgFacts() { return new PgFacts(PG.createCalls(), PG.lookupCalls(), PG.transactionCount(), PG.requests()); }

    private String inputChecksum(Order first, Order second, Order third, Order... late) throws Exception {
        String instant = new DateTimeFormatterBuilder().appendInstant(9).toFormatter().format(CONFIRMED_AT);
        String input = first.id() + "\t7\t2\t" + instant + "\n" + first.id() + "\t8\t1\t" + instant + "\n"
            + second.id() + "\t7\t3\t" + instant + "\n" + third.id() + "\t9\t5\t" + instant + "\n";
        if (late.length == 1) { input += late[0].id() + "\t7\t4\t" + instant + "\n"; }
        return sha256(input);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String id(int revision) { return "weekly-20260817-r" + revision; }

    private static AdminClient newAdmin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000, AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000));
    }

    private static LocalTestPg newPg() {
        try { return new LocalTestPg(); } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private record Order(long id) { String eventId() { return "order-confirmed:v1:" + id; } }
    private record PgFacts(int creates, int lookups, int transactions, List<LocalTestPg.CapturedRequest> requests) { }
}
