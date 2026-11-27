package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssuanceService;
import com.loopers.application.order.PaymentConfirmationTiming;
import com.loopers.application.outbox.OutboxRelay;
import com.loopers.domain.outbox.OutboxSender;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.interfaces.api.order.CheckoutIncidentDatabase.OrderFact;
import com.loopers.interfaces.api.order.CheckoutIncidentDatabase.Safeguards;
import com.loopers.support.AdminMockMvc;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "management.server.port=0", "management.server.address=127.0.0.1",
    "management.metrics.system.diskspace.paths=/",
    "commerce.payment-observation.enabled=true", "commerce.payment-observation.refresh-interval=250ms",
    "commerce.outbox-relay.enabled=false", "commerce.order-events.enabled=false"
})
@AutoConfigureObservability
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class CheckoutIncidentTest {
    private static final int UNIT_PRICE = 100;
    private static final int INITIAL_STOCK = 30;
    private static final LocalTestPg PG = startPg();
    @Container private static final KafkaContainer BROKER = new KafkaContainer("apache/kafka:3.8.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @Container private static final MySQLContainer<?> ACTIVITY_DB = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w10_owned_purchase_ranking");
    private static final String TOPIC = "w10-confirmed-" + UUID.randomUUID();
    private static final String GROUP = "w10-purchases-" + UUID.randomUUID();
    @TempDir private Path childLogs;
    @Autowired private TestRestTemplate rest;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FixtureUserInitializer users;
    @Autowired private CouponIssuanceService coupons;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private OutboxRelay relay;
    @Autowired private OutboxSender sender;
    @LocalManagementPort private int managementPort;
    @LocalServerPort private int applicationPort;
    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;
    @TestBean private PaymentConfirmationTiming paymentConfirmationTiming;
    @TestBean(name = "orderPaymentClock", methodName = "fixedClock") private Clock orderClock;
    @TestBean(name = "outboxClock", methodName = "fixedClock") private Clock outboxClock;

    static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC);
    }

    static PaymentConfirmationTiming paymentConfirmationTiming() {
        return new FastConfirmationTiming(PG);
    }

    static LocalTestPg startPg() {
        try {
            return new LocalTestPg();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void isolatedServices(DynamicPropertyRegistry registry) {
        new MySqlTestContainersConfig();
        new RedisTestContainersConfig();
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::getBootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", BROKER::getBootstrapServers);
        registry.add("commerce.ranking.query.enabled", () -> true);
        registry.add("commerce.ranking.query.jdbc-url", ACTIVITY_DB::getJdbcUrl);
        registry.add("commerce.ranking.query.username", ACTIVITY_DB::getUsername);
        registry.add("commerce.ranking.query.password", ACTIVITY_DB::getPassword);
        registry.add("commerce.test-pg.enabled", () -> true);
        registry.add("commerce.test-pg.base-url", () -> PG.baseUri().toString());
        registry.add("commerce.test-pg.callback-url", () -> PG.callbackUri().toString());
    }

    @AfterAll
    static void closePg() {
        PG.close();
    }

    @AfterEach
    void releaseIsolatedDatabase() {
        cleanup.truncateAllTables();
    }

    @Test
    @Timeout(240)
    void recoversLostApprovalsAndCountsEveryConfirmedPurchaseOnceInThePublishedRanking(CapturedOutput output) throws Exception {
        var evidence = CheckoutIncidentEvidence.start(json);
        var db = new CheckoutIncidentDatabase(jdbc);
        var timing = (FastConfirmationTiming) paymentConfirmationTiming;
        Set<Long> injected = new TreeSet<>();
        String phase = "baseline";
        try {
            // The external test driver shares this JVM. Its requests should not become API client metrics.
            rest.getRestTemplate().setObservationRegistry(ObservationRegistry.NOOP);
            assertThat(managementPort).isNotEqualTo(applicationPort);
            assertThat(PG.isCreateResponseLossDisabled()).isTrue();
            assertThat(timing.areLookupsAllowed()).isTrue();
            users.initialize();
            success(post("/api/v1/points/charge", "alice", Map.of("amount", 1000)), 200);
            db.seedOwnedCoupon(coupons, 1L);
            Safeguards original = db.safeguards();
            long productId = stockProduct();
            assertThat(db.availableStock(productId)).isEqualTo(INITIAL_STOCK);
            evidence.json("run.json", Map.of("runId", evidence.directory().getFileName().toString(),
                "businessClock", orderClock.instant().toString(), "productId", productId,
                "quantityPerOrder", 1, "unitPrice", UNIT_PRICE, "startingStock", INITIAL_STOCK,
                "sourceRevision", System.getenv().getOrDefault("W10_SOURCE_REVISION", "local working tree"),
                "applicationPort", applicationPort, "managementPort", managementPort));
            Map<Long, UUID> normal = drafts(productId);
            var normalResponses = confirm(normal, Set.of());
            Snapshot baseline = observe(phase, db, productId, normal, normalResponses, output, evidence);
            assertThat(baseline.unknown()).isEmpty();
            assertState(baseline, productId, 10, 20, original);
            assertNormalLookups(normal.keySet(), baseline);
            assertThat(normalResponses.values()).containsOnly(200);

            phase = "fault";
            Map<Long, UUID> affectedGroup = drafts(productId);
            assertThat(affectedGroup.keySet()).doesNotContainAnyElementsOf(normal.keySet());
            evidence.json("cohorts.json", Map.of("baseline", normal, "faultAndRecovery", affectedGroup));
            List<Long> ids = List.copyOf(affectedGroup.keySet());
            injected.addAll(List.of(ids.get(1), ids.get(4), ids.get(8)));
            for (long id : injected) {
                PG.loseSuccessfulCreateResponseFor(providerId(id));
                timing.expireAfterPgCreate(confirmPath(id), providerId(id));
            }
            Map<Long, UUID> all = new LinkedHashMap<>(normal);
            all.putAll(affectedGroup);
            Map<Long, Integer> firstResponses = new LinkedHashMap<>(normalResponses);
            firstResponses.putAll(confirm(affectedGroup, injected));
            Snapshot fault = observe(phase, db, productId, all, firstResponses, output, evidence);
            var candidates = CheckoutIncidentEvidence.lostResponseCandidates(fault.logs(), affectedGroup);
            var pgSuccess = affectedGroup.keySet().stream().filter(id -> fault.pg().get(id).status().equals("SUCCESS"))
                .collect(Collectors.toCollection(TreeSet::new));
            evidence.diagnosis(candidates, fault.unknown(), pgSuccess);
            var sealed = evidence.sealFault();
            evidence.verifyFault(sealed);
            assertThat(candidates).as("Orders identified by CREATE/NETWORK and a committed UNKNOWN").hasSize(3)
                .containsExactlyInAnyOrderElementsOf(fault.unknown());
            assertThat(pgSuccess).containsAll(candidates).hasSize(10);
            assertThat(candidates).containsExactlyInAnyOrderElementsOf(injected);
            assertState(fault, productId, 17, 10, original);
            normal.keySet().forEach(id -> assertThat(fault.orders().get(id)).isEqualTo(baseline.orders().get(id)));
            assertNormalLookups(affectedGroup.keySet().stream().filter(id -> !candidates.contains(id)).toList(), fault);
            all.keySet().forEach(id -> assertThat(firstResponses.get(id)).isEqualTo(candidates.contains(id) ? 202 : 200));

            phase = "recovery";
            injected.forEach(id -> PG.restoreCreateResponseFor(providerId(id)));
            timing.allowLookups();
            assertThat(PG.isCreateResponseLossDisabled()).isTrue();
            assertThat(timing.areLookupsAllowed()).isTrue();
            int before = PG.createCalls();
            reconcileAsOwner(candidates);
            Snapshot restored = observe(phase, db, productId, all, firstResponses, output, evidence);
            assertThat(restored.unknown()).isEmpty();
            assertState(restored, productId, 20, 10, original);
            assertThat(restored.pg()).isEqualTo(fault.pg());
            verifyRecoveryAndReplay(candidates, fault, restored);
            assertThat(db.orders(all.keySet())).isEqualTo(restored.orders());
            assertThat(db.safeguards()).isEqualTo(original);
            assertThat(db.availableStock(productId)).isEqualTo(fault.availableStock());
            assertThat(db.outboxCount()).isEqualTo(20);
            assertThat(PG.createCalls()).isEqualTo(before);
            assertThat(PG.transactionCount()).isEqualTo(20);
            evidence.verifyFault(sealed);
            phase = "downstream";
            int lookupsBeforeRanking = PG.lookupCalls();
            verifyWeeklyRanking(evidence, restored.orders(), normal.keySet(), affectedGroup.keySet(),
                candidates.iterator().next(), productId);
            long published = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message WHERE status='PUBLISHED'", Long.class);
            assertThat(published).as("All twenty confirmed purchases reached the broker").isEqualTo(20);
            db.orders(all.keySet()).forEach((id, after) -> {
                assertThat(after.outboxStatus()).isEqualTo("PUBLISHED");
                assertThat(after).usingRecursiveComparison().ignoringFields("outboxStatus").isEqualTo(restored.orders().get(id));
            });
            assertThat(db.availableStock(productId)).isEqualTo(restored.availableStock());
            assertThat(db.safeguards()).isEqualTo(original);
            assertThat(PG.createCalls()).isEqualTo(before);
            assertThat(PG.transactionCount()).isEqualTo(20);
            assertThat(PG.lookupCalls()).isEqualTo(lookupsBeforeRanking);
            evidence.verifyFault(sealed);
            evidence.json("verified.json", Map.of("recoveredOrders", candidates, "createDelta", PG.createCalls() - before,
                "pgTransactions", PG.transactionCount(), "repeatedReconcileAndCallback", candidates.size(),
                "faultHashesVerified", true, "outboxRows", db.outboxCount(), "publishedOutboxRows", published,
                "downstreamPgLookups", PG.lookupCalls() - lookupsBeforeRanking, "finalStock", db.availableStock(productId)));
        } catch (Exception | AssertionError failure) {
            try {
                evidence.failure(phase, failure);
            } catch (IOException writeFailure) {
                failure.addSuppressed(writeFailure);
            }
            throw failure;
        } finally {
            injected.forEach(id -> PG.restoreCreateResponseFor(providerId(id)));
            timing.allowLookups();
        }
    }

    private void verifyWeeklyRanking(CheckoutIncidentEvidence evidence, Map<Long, OrderFact> orders,
                                     Set<Long> baseline, Set<Long> faultCohort, long replayOrder, long productId) throws Exception {
        var baselineTimes = confirmedTimes(orders, baseline);
        var faultTimes = confirmedTimes(orders, faultCohort);
        assertThat(faultTimes).containsKey(replayOrder);
        CheckoutIncidentStream.createTopic(BROKER, TOPIC);
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl(ACTIVITY_DB.getJdbcUrl());
            pool.setUsername(ACTIVITY_DB.getUsername());
            pool.setPassword(ACTIVITY_DB.getPassword());
            pool.setMaximumPoolSize(3);
            pool.setMinimumIdle(0);
            pool.setConnectionTimeout(3000);
            pool.addDataSourceProperty("connectTimeout", "3000");
            pool.addDataSourceProperty("socketTimeout", "10000");
            new ResourceDatabasePopulator(new FileSystemResource(System.getProperty("w6.streamer.schema"))).execute(pool);
            var activity = new JdbcTemplate(pool);
            activity.setQueryTimeout(5);
            var ranking = new CheckoutIncidentRanking(ACTIVITY_DB, activity, rest, LocalDate.of(2026, 11, 23));
            ranking.initialize();
            ranking.assertUnpublished();
            try (var stream = CheckoutIncidentStream.start(BROKER, ACTIVITY_DB, TOPIC, GROUP, childLogs.resolve("streamer.log"),
                    jdbc, activity, relay, sender, json)) {
                stream.publish(baselineTimes, productId);
                var prior = ranking.publish(1, productId, baselineTimes);
                assertThat(prior.score()).isEqualTo(10);
                stream.publish(faultTimes, productId);
                stream.replay(replayOrder);
                evidence.json("stream.json", stream.evidence());

                ranking.assertStillPublished(prior);
                var current = ranking.publish(2, productId, confirmedTimes(orders, orders.keySet()));
                assertThat(current.score()).isEqualTo(20);
                assertThat(current.snapshotId()).isNotEqualTo(prior.snapshotId());
                assertThat(current.jobInstanceId()).isNotEqualTo(prior.jobInstanceId());
                ranking.assertSnapshotUnchanged(prior);
                evidence.json("ranking.json", Map.of("baseline", prior, "allOrders", current));
            }
        }
    }

    private Map<Long, Instant> confirmedTimes(Map<Long, OrderFact> orders, Set<Long> ids) {
        var times = new LinkedHashMap<Long, Instant>();
        for (long id : ids) {
            // Commerce persists normalized UTC in MySQL DATETIME. Use each row's first confirmation time.
            String stored = orders.get(id).confirmedAt();
            Instant confirmedAt = LocalDateTime.parse(stored.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
            assertThat(confirmedAt).isEqualTo(orderClock.instant());
            times.put(id, confirmedAt);
        }
        return times;
    }

    private void reconcileAsOwner(Set<Long> candidates) {
        for (long id : candidates) {
            int lookups = PG.lookupCalls();
            assertThat(getOrder(id, "bob").getStatusCode().value()).isEqualTo(404);
            assertThat(post(reconcilePath(id), "bob", null).getStatusCode().value()).isEqualTo(404);
            assertThat(success(getOrder(id, "alice"), 200).required("payment").required("status").asText()).isEqualTo("UNKNOWN");
            assertThat(PG.lookupCalls()).isEqualTo(lookups);
            assertThat(success(post(reconcilePath(id), "alice", null), 200).required("status").asText()).isEqualTo("CONFIRMED");
        }
    }

    private void verifyRecoveryAndReplay(Set<Long> candidates, Snapshot fault, Snapshot restored) {
        for (long id : restored.orders().keySet()) {
            OrderFact recovered = restored.orders().get(id);
            if (candidates.contains(id)) {
                recovered.assertSameOriginalAttemptAndItem(fault.orders().get(id));
                assertThat(restored.logs()).anyMatch(line -> line.contains("source=RECONCILE operation=LOOKUP_BY_ORDER ")
                    && line.contains("orderId=" + id + " ") && line.contains("pgStatus=SUCCESS "));
                assertThat(restored.logs()).anyMatch(line -> line.contains("phase=COMPLETED observation=SUCCESS ")
                    && line.contains("orderId=" + id + " ") && line.endsWith("decision=APPLIED txOutcome=COMMITTED"));
                success(post(reconcilePath(id), "alice", null), 200);
                var notification = Map.of("providerOrderId", recovered.providerOrderId(),
                    "transactionKey", recovered.transactionKey(), "status", "SUCCESS");
                assertThat(success(post("/api/v1/payments/callback", null, notification), 200)
                    .required("acknowledged").asBoolean()).isTrue();
            } else {
                assertThat(recovered).isEqualTo(fault.orders().get(id));
            }
        }
    }

    private long stockProduct() {
        long brand = success(AdminMockMvc.exchange(mvc, HttpMethod.POST, "/api-admin/v1/brands", "admin",
            "{\"name\":\"W10 brand\"}"), 201).required("brandId").asLong();
        return success(AdminMockMvc.exchange(mvc, HttpMethod.POST, "/api-admin/v1/products", "admin",
            "{\"brandId\":%d,\"name\":\"W10 product\",\"price\":%d,\"stockQuantity\":%d}"
                .formatted(brand, UNIT_PRICE, INITIAL_STOCK)), 201).required("productId").asLong();
    }

    private Map<Long, UUID> drafts(long productId) {
        Map<Long, UUID> orders = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            var draft = success(post("/api/v1/orders", "alice", Map.of("items",
                List.of(Map.of("productId", productId, "quantity", 1)))), 201);
            assertThat(draft.required("status").asText()).isEqualTo("DRAFT");
            orders.put(draft.required("orderId").asLong(), UUID.randomUUID());
        }
        assertThat(orders).hasSize(10);
        return orders;
    }

    private Map<Long, Integer> confirm(Map<Long, UUID> orders, Set<Long> drops) {
        Map<Long, Integer> responses = new LinkedHashMap<>();
        orders.forEach((id, key) -> {
            if (!drops.contains(id)) {
                PG.acceptPendingThenSucceedFor(providerId(id));
            }
            var response = post(confirmPath(id), "alice", Map.of("paymentAttemptKey", key,
                "cardType", "TEST", "cardNo", "0000-0000-0000-0000"));
            int status = response.getStatusCode().value();
            assertThat(status).isIn(200, 202);
            JsonNode data = success(response, status);
            assertThat(data.required("orderId").asLong()).isEqualTo(id);
            assertThat(data.required("payment").required("status").asText()).isEqualTo(status == 200 ? "SUCCESS" : "UNKNOWN");
            responses.put(id, status);
        });
        return responses;
    }

    private Snapshot observe(String phase, CheckoutIncidentDatabase db, long product, Map<Long, UUID> orders,
                             Map<Long, Integer> firstResponses, CapturedOutput output, CheckoutIncidentEvidence evidence) throws Exception {
        var committed = db.orders(orders.keySet());
        var unknown = db.unknownOrders(orders.keySet());
        var health = management("health");
        assertThat(json.readTree(health).required("status").asText()).isEqualTo("UP");
        String scrape = awaitScrape(unknown.size());
        Map<Long, PgFact> pg = readExistingTransactions(committed, orders);
        var creates = PG.requests().stream().filter(request -> "POST".equals(request.method()))
            .collect(Collectors.groupingBy(LocalTestPg.CapturedRequest::orderId, Collectors.counting()));
        assertThat(creates.keySet()).containsExactlyInAnyOrderElementsOf(pg.values().stream().map(PgFact::orderId).toList());
        assertThat(creates.values()).containsOnly(1L);
        assertThat(PG.createCalls()).isEqualTo(orders.size());
        assertThat(PG.transactionCount()).isEqualTo(orders.size());
        assertThat(db.orders(orders.keySet())).as("Read-only diagnosis preserves committed commerce state").isEqualTo(committed);
        List<String> logs = CheckoutIncidentEvidence.orderLogs(output.getAll(), orders);
        var state = new Snapshot(committed, pg, unknown, db.availableStock(product), db.outboxCount(),
            db.safeguards(), firstResponses, creates, PG.createCalls(), PG.transactionCount(), logs);
        evidence.phase(phase, health, scrape, state, logs);
        return state;
    }

    private String awaitScrape(int unknown) {
        return await().atMost(Duration.ofSeconds(7)).pollInterval(Duration.ofMillis(100))
            .until(() -> management("prometheus"), raw -> {
                List<String> samples = raw.lines().filter(line -> line.startsWith("commerce_payment_unknown{")
                    || line.startsWith("commerce_payment_unknown ")).toList();
                return samples.size() == 1 && Double.parseDouble(samples.getFirst()
                    .substring(samples.getFirst().lastIndexOf(' ') + 1)) == unknown;
            });
    }

    private Map<Long, PgFact> readExistingTransactions(Map<Long, OrderFact> committed, Map<Long, UUID> orders) throws IOException {
        Map<Long, PgFact> pg = new LinkedHashMap<>();
        for (OrderFact row : committed.values()) {
            assertThat(row.attemptKey()).isEqualTo(orders.get(row.orderId()));
            var headers = new HttpHeaders();
            headers.set("X-USER-ID", row.providerAccount());
            var transactions = rest.exchange(PG.baseUri().resolve("/api/v1/payments?orderId=" + row.providerOrderId()),
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            assertThat(transactions.getStatusCode().value()).isEqualTo(200);
            assertThat(transactions.getBody().isArray()).isTrue();
            assertThat(transactions.getBody().size()).isEqualTo(1);
            var actual = json.treeToValue(transactions.getBody().get(0), PgFact.class);
            assertThat(actual.orderId()).isEqualTo(row.providerOrderId());
            assertThat(actual.amount()).isEqualTo(row.amount());
            assertThat(actual.transactionKey()).isNotBlank();
            if (row.transactionKey() != null) {
                assertThat(row.transactionKey()).isEqualTo(actual.transactionKey());
            }
            pg.put(row.orderId(), actual);
        }
        assertThat(pg.values()).extracting(PgFact::transactionKey).doesNotHaveDuplicates();
        return pg;
    }

    private void assertState(Snapshot state, long productId, int confirmed, int stock, Safeguards safeguards) {
        assertThat(state.pg().values()).extracting(PgFact::status).containsOnly("SUCCESS");
        assertThat(state.availableStock()).isEqualTo(stock);
        assertThat(state.outboxRows()).isEqualTo(confirmed);
        assertThat(state.safeguards()).isEqualTo(safeguards);
        assertThat(state.orders().size() - state.unknown().size()).isEqualTo(confirmed);
        state.orders().forEach((id, row) -> {
            if (state.unknown().contains(id)) {
                row.assertUnknown(productId, UNIT_PRICE);
            } else {
                row.assertConfirmed(productId, UNIT_PRICE);
            }
        });
    }

    private void assertNormalLookups(Collection<Long> ids, Snapshot state) {
        for (long id : ids) {
            String identity = " orderId=" + id + " attemptKey=" + state.orders().get(id).attemptKey() + " ";
            assertThat(state.logs()).anyMatch(line -> line.contains(identity) && line.contains("source=CONFIRM operation=CREATE ")
                && line.contains("pgStatus=PENDING "));
            assertThat(state.logs()).anyMatch(line -> line.contains(identity) && line.contains("source=CONFIRM operation=LOOKUP_BY_KEY ")
                && line.contains("pgStatus=SUCCESS "));
        }
    }

    private String management(String endpoint) {
        var response = rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/" + endpoint, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> post(String path, String user, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            headers.set("X-USER-ID", user);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> getOrder(long orderId, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", user);
        return rest.exchange("/api/v1/orders/{id}", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class, orderId);
    }

    private JsonNode success(ResponseEntity<JsonNode> response, int expected) {
        assertThat(response.getStatusCode().value()).isEqualTo(expected);
        assertThat(response.getBody().required("meta").required("result").asText()).isEqualTo("SUCCESS");
        return response.getBody().path("data");
    }

    private static String confirmPath(long id) {
        return "/api/v1/orders/" + id + "/confirm";
    }

    private static String reconcilePath(long id) {
        return "/api/v1/orders/" + id + "/payment/reconcile";
    }

    private static String providerId(long id) {
        return "LP-ORD-%010d".formatted(id);
    }

    record PgFact(String transactionKey, String orderId, long amount, String status) { }

    record Snapshot(Map<Long, OrderFact> orders, Map<Long, PgFact> pg, Set<Long> unknown, int availableStock,
                    long outboxRows, Safeguards safeguards, Map<Long, Integer> firstConfirmHttpStatus,
                    Map<String, Long> createCallsByProviderOrder, int createCalls, int transactions, @JsonIgnore List<String> logs) { }
}
