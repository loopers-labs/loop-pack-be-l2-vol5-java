package com.loopers.crossapp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderConfirmedOutboxRelay;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.payment.HttpPaymentGateway;
import com.loopers.infrastructure.payment.PaymentGatewayProperties;
import com.loopers.infrastructure.payment.TestPaymentPgServer;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.interfaces.api.order.OrderV1Dto;
import com.loopers.interfaces.api.payment.PaymentV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@AutoConfigureObservability(tracing = false)
@SpringBootTest(
    classes = {CrossAppTestConfig.class, CheckoutIncidentE2ETest.IncidentConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "server.address=127.0.0.1",
        "management.server.address=127.0.0.1",
        "management.server.port=0",
        "payment.pg.enabled=true",
        "payment.pg.read-timeout=PT0.5S",
        "test.fake-pg.enabled=false",
        "payment.lookup.enabled=false",
        "payment.metrics.unresolved.enabled=true",
        "payment.metrics.unresolved.fixed-delay=PT0.2S",
        "outbox.relay.enabled=false",
        "spring.batch.job.enabled=false",
        "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
        // commerce-api 의 application.yml 에는 batch 설정이 없다. 기본 embedded 는 MySQL 에 BATCH_ 테이블을 만들지 않는다.
        "spring.batch.jdbc.initialize-schema=always",
        "order-events.topic=" + CheckoutIncidentE2ETest.TOPIC,
        "purchase-activity.topic=" + CheckoutIncidentE2ETest.TOPIC,
        "purchase-activity.group-id=" + CheckoutIncidentE2ETest.GROUP,
        "purchase-activity.fetch-min-bytes=1",
        "purchase-activity.fetch-max-wait-ms=100",
        "purchase-activity.retry.max-retries=2",
        "purchase-activity.retry.initial-backoff-ms=100",
        "purchase-activity.retry.multiplier=2",
        "purchase-activity.dead-letter.send-timeout-ms=1000"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutIncidentE2ETest {
    static final String TOPIC = "order-events-CheckoutIncidentE2ETest";
    static final String GROUP = "purchase-activity-CheckoutIncidentE2ETest";
    private static final String CARD = "0000-0000-0000-0000";
    private static final int INITIAL_STOCK = 30;
    private static final String COUNT = "commerce_payment_unresolved";
    private static final String LAST_SUCCESS = COUNT + "_refresh_last_success_timestamp_seconds";
    private static final String OLDEST = COUNT + "_oldest_created_timestamp_seconds";
    private static final String REFRESH_TOTAL = COUNT + "_refresh_total";
    private static final Set<String> BOUNDARIES = Set.of(
        "pg.create", "pg.lookup", "payment.create", "payment.callback", "payment.apply", "payment.reconcile");

    static {
        KafkaTestContainersConfig.createTopic(TOPIC, 1);
    }

    private static TestPaymentPgServer pg;
    private static IncidentEvidence evidence;
    private static boolean wroteManifest;

    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserJpaRepository users;
    @Autowired private ProductJpaRepository products;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @Autowired private Environment environment;
    @Autowired private PaymentGateway gateway;
    @Autowired private PaymentGatewayProperties pgProperties;
    @Autowired private OrderConfirmedOutboxRelay relay;
    @Autowired private JobLauncher jobLauncher;
    @Autowired @Qualifier(WeeklyRankingJobConfig.JOB_NAME) private Job weeklyRankingJob;
    @Value("${local.server.port}") private int apiPort;
    @Value("${local.management.port}") private int managementPort;

    @TestConfiguration
    @EnableScheduling
    static class IncidentConfig {
        @Bean
        Clock incidentClock() {
            return Clock.systemUTC();
        }
    }

    @BeforeAll
    static void startFixture() throws IOException {
        evidence = new IncidentEvidence();
        pg = new TestPaymentPgServer();
        pg.enableTransactionStore();
    }

    @DynamicPropertySource
    static void pgUrls(DynamicPropertyRegistry registry) {
        registry.add("payment.pg.base-url", pg::baseUrl);
        registry.add("payment.pg.callback-url", () -> pg.baseUrl() + "/api/v1/payments/callback");
    }

    @AfterAll
    static void closeFixture() throws IOException {
        try {
            if (evidence != null && !wroteManifest) {
                evidence.write(new ObjectMapper(), "run.json", Map.of(
                    "runId", evidence.runId, "startedAt", evidence.startedAt.toString(), "endedAt", Instant.now().toString(),
                    "result", "FAILED", "detail", "No test body result recorded. See the Gradle test report."));
            }
        } finally {
            if (pg != null) {
                pg.close();
            }
        }
    }

    @Test
    void baselineFaultAndDiagnosisInOneRun() throws Throwable {
        var appender = new ListAppender<ILoggingEvent>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger("com.loopers");
        logger.addAppender(appender);
        Map<String, Object> run = new LinkedHashMap<>();
        Map<String, Object> stages = new LinkedHashMap<>();
        run.put("runId", evidence.runId);
        run.put("startedAt", evidence.startedAt.toString());
        run.put("stages", stages);
        Throwable failure = null;
        try {
            run.put("environment", Map.of(
                "profiles", environment.getActiveProfiles(), "java", System.getProperty("java.version"),
                "mysql", jdbc.queryForObject("select version()", String.class), "apiPort", apiPort,
                "managementPort", managementPort, "pgBaseUrl", pg.baseUrl(),
                "pgReadTimeout", pgProperties.readTimeout().toString(), "lookupCircuitBreaker", pgProperties.circuitBreaker(),
                "metricDelay", environment.getRequiredProperty("payment.metrics.unresolved.fixed-delay"),
                "automaticPaymentLookup", environment.getRequiredProperty("payment.lookup.enabled", Boolean.class)));
            run.put("initialCounts", Map.of(
                "orders", count("select count(*) from orders"),
                "attempts", count("select count(*) from payment_attempts"),
                "reservations", count("select count(*) from stock_reservations"),
                "pgCreates", pg.createCount(), "pgTransactions", pg.storedTransactions().size()));
            assertThat((Map<?, ?>) run.get("initialCounts")).allSatisfy((key, value) ->
                assertThat(((Number) value).longValue()).as(key.toString()).isZero());
            assertThat(gateway).isInstanceOf(HttpPaymentGateway.class);

            long userId = users.save(new User()).getId();
            Product product = new Product(1L, "S5 synthetic product", new Price(1000L));
            product.changeStock(INITIAL_STOCK);
            long productId = products.save(product).getId();
            try {
                Stage baseline = execute("baseline", userId, productId, 0, 0, appender, stages);
                assertThat(baseline.snapshot.stock()).isEqualTo(20);

                Stage fault = execute("fault", userId, productId, 3, baseline.lastSuccess(), appender, stages);
                assertThat(fault.snapshot.stock()).isEqualTo(10);
                assertThat(fault.snapshot.orders()).containsAll(baseline.snapshot.orders());
                assertThat(fault.snapshot.transactions()).containsAll(baseline.snapshot.transactions());
                assertThat(fault.orderIds()).doesNotContainAnyElementsOf(baseline.orderIds());
                assertThatThrownBy(() -> evidence.write(mapper, "fault.json", Map.of()))
                    .isInstanceOf(FileAlreadyExistsException.class);
                diagnose(fault, productId, boundaryLogs(appender, 0), stages);
                Ranking r1 = publishRankingBeforeRecovery(productId, stages);
                recover(fault, userId, productId, appender, stages, r1);
            } finally {
                databaseCleanUp.truncateAllTables();
            }
            run.put("result", "PASSED");
        } catch (Throwable e) {
            failure = e;
            run.put("result", e instanceof AssertionError ? "FAILED" : "ERROR");
            run.put("failureType", e.getClass().getName());
            throw e;
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            run.put("endedAt", Instant.now().toString());
            try {
                evidence.write(mapper, "run.json", run);
                wroteManifest = true;
            } catch (IOException e) {
                if (failure == null) {
                    throw e;
                }
                failure.addSuppressed(e);
            }
        }
    }

    private Stage execute(
        String phase, long userId, long productId, int faultCount, double previousRefresh,
        ListAppender<ILoggingEvent> logs, Map<String, Object> stages
    ) throws Throwable {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Long> orderIds = new ArrayList<>();
        Map<Long, String> faultProviderIds = new LinkedHashMap<>();
        data.put("runId", evidence.runId);
        data.put("phase", phase);
        data.put("startedAt", Instant.now().toString());
        data.put("orderIds", orderIds);
        data.put("faultTargets", faultProviderIds);
        data.put("schedulerVerified", false);
        int logStart = logs.list.size();
        AtomicReference<Observation> observation = new AtomicReference<>();
        Throwable failure = null;
        try {
            for (int i = 0; i < 10; i++) {
                var request = new OrderV1Dto.CreateRequest(List.of(new OrderV1Dto.CreateRequest.ItemRequest(productId, 1)));
                long orderId = post("/api/v1/orders", request, userId, 201).path("orderId").asLong();
                assertThat(orderId).isPositive();
                orderIds.add(orderId);
            }
            for (long orderId : orderIds.subList(0, faultCount)) {
                String providerId = PaymentAttempt.providerOrderIdOf(orderId);
                faultProviderIds.put(orderId, providerId);
                pg.dropCreateResponseFor(providerId);
            }
            for (long id : orderIds) {
                boolean fault = faultProviderIds.containsKey(id);
                var request = new OrderV1Dto.ConfirmRequest("s5-" + evidence.runId + "-" + id, "TEST", CARD);
                JsonNode confirm = post("/api/v1/orders/" + id + "/confirm", request, userId, 202);
                assertThat(confirm.path("attemptStatus").asText()).isEqualTo(fault ? "UNKNOWN" : "PENDING");
                if (!fault) {
                    String providerId = PaymentAttempt.providerOrderIdOf(id);
                    assertThat(pg.transactionOf(providerId)).isNotNull();
                    pg.markSuccess(providerId);
                    var callback = new PaymentV1Dto.CallbackRequest(providerId, pg.transactionOf(providerId).transactionKey(), "SUCCESS");
                    assertThat(post("/api/v1/payments/callback", callback, null, 200).path("attemptStatus").asText())
                        .isEqualTo("SUCCESS");
                }
            }

            long completedAtMillis = System.currentTimeMillis();
            Snapshot snapshot = snapshot(productId);
            data.put("snapshot", snapshot);
            assertState(snapshot, orderIds, faultProviderIds);
            HttpObservation health = getManagement("/actuator/health");
            data.put("health", health);
            assertThat(health.status()).isEqualTo(200);
            assertThat(mapper.readTree(health.body()).path("status").asText()).isEqualTo("UP");
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                HttpObservation prometheus = getManagement("/actuator/prometheus");
                double last = sample(prometheus.body(), LAST_SUCCESS, null);
                double age = prometheus.observedAtEpochMillis() / 1000d - last;
                observation.set(new Observation(prometheus, last, age,
                    sample(prometheus.body(), COUNT, "state=\"unknown\""),
                    sample(prometheus.body(), REFRESH_TOTAL, "result=\"success\"")));
                assertThat(prometheus.status()).isEqualTo(200);
                assertThat(last).isGreaterThan(previousRefresh).isGreaterThanOrEqualTo(completedAtMillis / 1000d);
                assertThat(age).isBetween(0d, 5d);
                assertThat(observation.get().successCounter()).isPositive();
                assertThat(observation.get().unknown()).isEqualTo(faultCount);
                assertThat(sample(prometheus.body(), COUNT, "state=\"requested\"")).isZero();
                assertThat(sample(prometheus.body(), COUNT, "state=\"pending\"")).isZero();
                double oldest = sample(prometheus.body(), OLDEST, "state=\"unknown\"");
                if (faultCount == 0) {
                    assertThat(oldest).isNaN();
                } else {
                    assertThat(oldest).isPositive().isLessThanOrEqualTo(last);
                }
            });
            data.put("schedulerVerified", true);
            List<BoundaryLog> captured = boundaryLogs(logs, logStart);
            assertThat(captured).anyMatch(log -> log.message().startsWith("pg.lookup outcome=FOUND"));
            assertThat(captured).anyMatch(log -> log.message().startsWith("payment.apply phase=committed"));
            for (var target : faultProviderIds.entrySet()) {
                assertThat(captured).anyMatch(log -> log.message().startsWith("pg.create outcome=UNKNOWN providerOrderId=" + target.getValue() + " "));
                assertThat(captured).anyMatch(log -> log.message().startsWith("payment.create outcome=UNKNOWN orderId=" + target.getKey() + " ")
                    && log.message().contains("unknownRows=1"));
            }
            data.put("result", "PASSED");
            return new Stage(List.copyOf(orderIds), Map.copyOf(faultProviderIds), snapshot, observation.get().lastSuccess());
        } catch (Throwable e) {
            failure = e;
            data.put("result", e instanceof AssertionError ? "FAILED" : "ERROR");
            data.put("failureType", e.getClass().getName());
            throw e;
        } finally {
            stages.put(phase, data.get("result"));
            data.put("endedAt", Instant.now().toString());
            data.put("observation", observation.get());
            data.put("logs", boundaryLogs(logs, logStart));
            try {
                if (!data.containsKey("snapshot")) {
                    data.put("snapshot", snapshot(productId));
                }
                evidence.write(mapper, phase + ".json", data);
                if ("fault".equals(phase)) {
                    evidence.sealFault();
                }
            } catch (Exception e) {
                if (failure == null) {
                    throw e;
                }
                failure.addSuppressed(e);
            }
        }
    }

    private void diagnose(Stage fault, long productId, List<BoundaryLog> logs, Map<String, Object> stages) throws Throwable {
        var faultFile = evidence.directory.resolve("fault.json");
        var checksumFile = evidence.directory.resolve("fault.json.sha256");
        byte[] originalFault = Files.readAllBytes(faultFile);
        byte[] originalChecksum = Files.readAllBytes(checksumFile);
        Map<String, Object> diagnosis = new LinkedHashMap<>();
        List<Map<String, Object>> observations = new ArrayList<>();
        diagnosis.put("runId", evidence.runId);
        diagnosis.put("startedAt", Instant.now().toString());
        diagnosis.put("orders", observations);
        int firstRequest = pg.receivedRequests().size();
        Snapshot before = snapshot(productId);
        Map<String, List<Map<String, Object>>> dbBefore = diagnosisDbState();
        diagnosis.put("before", before);
        diagnosis.put("dbBefore", dbBefore);
        Throwable failure = null;
        try {
            assertThat(before).isEqualTo(fault.snapshot());
            assertThat(fault.faultTargets()).hasSize(3);
            for (long orderId : fault.orderIds()) {
                String providerId = fault.faultTargets().get(orderId);
                if (providerId != null) {
                    observeFaultOrder(orderId, providerId, before, logs, observations);
                }
            }
        } catch (Throwable e) {
            failure = e;
            diagnosis.put("failureType", e.getClass().getName());
            throw e;
        } finally {
            try {
                Snapshot after = snapshot(productId);
                Map<String, List<Map<String, Object>>> dbAfter = diagnosisDbState();
                var requests = pg.receivedRequests().stream().skip(firstRequest).toList();
                diagnosis.put("after", after);
                diagnosis.put("dbAfter", dbAfter);
                diagnosis.put("pgRequests", requests.stream()
                    .map(request -> Map.of("method", request.method(), "path", request.path(), "query", request.query())).toList());
                boolean unchanged = dbBefore.equals(dbAfter) && before.orders().equals(after.orders())
                    && before.transactions().equals(after.transactions()) && before.creates().equals(after.creates())
                    && before.createCount() == after.createCount() && before.stock() == after.stock();
                diagnosis.put("businessStateUnchanged", unchanged);
                boolean faultUnchanged = java.util.Arrays.equals(originalFault, Files.readAllBytes(faultFile))
                    && java.util.Arrays.equals(originalChecksum, Files.readAllBytes(checksumFile));
                diagnosis.put("faultEvidenceUnchanged", faultUnchanged);
                assertThat(unchanged).as("state changed during observation; diagnosis must be repeated against current state").isTrue();
                assertThat(faultUnchanged).isTrue();
                assertThat(requests).hasSize(3).allSatisfy(request -> {
                    assertThat(request.method()).isEqualTo("GET");
                    assertThat(request.path()).isEqualTo("/api/v1/payments");
                });
                assertThat(requests).extracting(request -> request.query().get("orderId"))
                    .containsExactlyInAnyOrderElementsOf(fault.faultTargets().values());
            } catch (Throwable verificationFailure) {
                if (failure != null) {
                    failure.addSuppressed(verificationFailure);
                } else {
                    failure = verificationFailure;
                    diagnosis.put("failureType", failure.getClass().getName());
                    throw verificationFailure;
                }
            } finally {
                String result = failure == null ? "PASSED" : "FAILED";
                stages.put("diagnose", result);
                diagnosis.put("result", result);
                diagnosis.put("endedAt", Instant.now().toString());
                try {
                    evidence.write(mapper, "diagnosis.json", diagnosis);
                } catch (IOException writeFailure) {
                    if (failure == null) {
                        stages.put("diagnose", "ERROR");
                        throw writeFailure;
                    }
                    failure.addSuppressed(writeFailure);
                }
            }
        }
    }

    private void observeFaultOrder(
        long orderId, String providerId, Snapshot before, List<BoundaryLog> logs, List<Map<String, Object>> observations
    ) throws IOException {
        var row = before.orders().stream().filter(it -> Long.valueOf(orderId).equals(it.get("order_id"))).findFirst().orElseThrow();
        var original = before.transactions().stream().filter(tx -> tx.providerOrderId().equals(providerId)).findFirst().orElseThrow();
        List<BoundaryLog> orderLogs = logs.stream()
            .filter(log -> (" " + log.message() + " ").contains(" orderId=" + orderId + " ")
                || (" " + log.message() + " ").contains(" providerOrderId=" + providerId + " ")
                || (" " + log.message() + " ").contains(" orderId=" + providerId + " "))
            .toList();
        var url = UriComponentsBuilder.fromUriString(pg.baseUrl()).path("/api/v1/payments")
            .queryParam("orderId", providerId).build().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", pgProperties.userId());
        var response = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        Map<String, Object> observed = new LinkedHashMap<>();
        observations.add(observed);
        observed.put("internal", row);
        observed.put("faultPgTransaction", original);
        observed.put("logsBeforeDiagnosis", orderLogs);
        observed.put("pgLookup", new HttpObservation(response.getStatusCode().value(), response.getBody(), System.currentTimeMillis()));
        observed.put("verdict", "UNDETERMINED");
        boolean createUnconfirmed = orderLogs.stream().anyMatch(log -> log.message().startsWith("pg.create outcome=UNKNOWN ")
            && (log.message().contains("HttpTimeoutException") || log.message().contains("CancellationException")));
        boolean internalUnknownRecorded = orderLogs.stream().anyMatch(log -> log.message().startsWith("payment.create outcome=UNKNOWN ")
            && log.message().contains("unknownRows=1"));
        boolean applyObserved = orderLogs.stream().anyMatch(log -> log.message().startsWith("payment.apply "));
        observed.put("createUnconfirmed", createUnconfirmed);
        observed.put("internalUnknownRecorded", internalUnknownRecorded);
        observed.put("applyObserved", applyObserved);
        observed.put("previousPgLookups", before.lookups().get(providerId));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(1);
        JsonNode transaction = body.get(0);
        assertThat(transaction.path("orderId").asText()).isEqualTo(providerId);
        assertThat(transaction.path("transactionKey").asText()).isEqualTo(original.transactionKey());
        assertThat(transaction.path("amount").isIntegralNumber() && transaction.path("amount").canConvertToLong()).isTrue();
        assertThat(transaction.path("amount").longValue()).isEqualTo(original.amount()).isEqualTo(((Number) row.get("amount")).longValue());
        assertThat(transaction.path("status").asText()).isEqualTo("SUCCESS");
        if (createUnconfirmed && internalUnknownRecorded && !applyObserved && before.lookups().get(providerId) == 0L) {
            observed.put("verdict", "CREATE_RESPONSE_LOSS_BEST_SUPPORTED");
        }
    }


    /**
     * 복구 **이전**의 실제 구매 활동 17건(baseline 10 + fault 정상 7)을 진짜 relay·Kafka·consumer 로 처리하고 r1 을 공개한다.
     * 기대값을 손으로 심지 않는다. 장애 3건은 아직 확정되지 않았으므로 Outbox 행도 없다.
     */
    private Ranking publishRankingBeforeRecovery(long productId, Map<String, Object> stages) throws Throwable {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", evidence.runId);
        data.put("phase", "ranking-r1");
        data.put("startedAt", Instant.now().toString());
        Throwable failure = null;
        try {
            assertThat(count("select count(*) from order_confirmed_outbox")).isEqualTo(17);
            assertThat(count("select count(*) from order_confirmed_outbox where status = 'PENDING'")).isEqualTo(17);

            int published = 0;
            for (int round = 0; round < 10 && count("select count(*) from order_confirmed_outbox where status = 'PENDING'") > 0; round++) {
                published += relay.runOnce().published();
            }
            data.put("outboxPublished", published);
            assertThat(published).isEqualTo(17);
            assertThat(count("select count(*) from order_confirmed_outbox where status = 'PUBLISHED'")).isEqualTo(17);

            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                assertThat(count("select count(*) from product_purchase_activity")).isEqualTo(17);
                assertThat(count("select count(*) from processed_order_confirmation")).isEqualTo(17);
            });
            data.put("activitiesBeforeRecovery", count("select count(*) from product_purchase_activity"));
            data.put("ledgerBeforeRecovery", count("select count(*) from processed_order_confirmation"));
            assertThat(count("select coalesce(sum(quantity), 0) from product_purchase_activity")).isEqualTo(17);

            LocalDate periodStart = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            int revision = nextRevision(periodStart);
            String snapshotId = snapshotId(periodStart, revision);
            data.put("periodStart", periodStart.toString());
            data.put("snapshotId", snapshotId);
            JobExecution execution = jobLauncher.run(weeklyRankingJob, params(periodStart, snapshotId));
            data.put("jobStatus", execution.getStatus().toString());
            data.put("jobExitDescription", execution.getExitStatus().getExitDescription());
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(count("select count(*) from weekly_ranking_inputs where snapshot_id = '" + snapshotId + "'")).isEqualTo(17);

            JsonNode ranking = getApi("/api/v1/rankings/weekly?periodStart=" + periodStart);
            data.put("rankingResponse", mapper.convertValue(ranking, Map.class));
            assertThat(ranking.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(ranking.path("snapshotId").asText()).isEqualTo(snapshotId);
            JsonNode item = ranking.path("items").get(0);
            assertThat(item.path("productId").asLong()).isEqualTo(productId);
            assertThat(item.path("score").asLong()).isEqualTo(17);
            assertThat(item.path("rank").asInt()).isEqualTo(1);
            data.put("result", "PASSED");
            return new Ranking(periodStart, revision, snapshotId, 17L);
        } catch (Throwable e) {
            failure = e;
            data.put("result", e instanceof AssertionError ? "FAILED" : "ERROR");
            data.put("failureType", e.getClass().getName());
            throw e;
        } finally {
            stages.put("ranking-r1", data.get("result"));
            data.put("endedAt", Instant.now().toString());
            try {
                evidence.write(mapper, "ranking-r1.json", data);
            } catch (IOException writeFailure) {
                if (failure == null) {
                    throw writeFailure;
                }
                failure.addSuppressed(writeFailure);
            }
        }
    }

    /**
     * 같은 주문·같은 거래를 기존 reconcile API 로 복구한다. 새 create 는 보내지 않고 DB 를 직접 바꾸지 않는다.
     */
    private void recover(
        Stage fault, long userId, long productId, ListAppender<ILoggingEvent> logs, Map<String, Object> stages, Ranking r1
    ) throws Throwable {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> responses = new ArrayList<>();
        data.put("runId", evidence.runId);
        data.put("phase", "recovery");
        data.put("startedAt", Instant.now().toString());
        data.put("faultTargets", fault.faultTargets());
        data.put("reconcileResponses", responses);
        int logStart = logs.list.size();
        Snapshot before = snapshot(productId);
        Map<String, List<Map<String, Object>>> sideBefore = sideState();
        long outboxBefore = count("select count(*) from order_confirmed_outbox");
        data.put("before", before);
        data.put("sideStateBefore", sideBefore);
        data.put("outboxBefore", outboxBefore);
        Throwable failure = null;
        try {
            pg.clearResponseDrops();
            assertThat(pg.hasResponseDrops()).as("response-drop injection must be released before recovery").isFalse();

            for (var target : fault.faultTargets().entrySet()) {
                long orderId = target.getKey();
                String providerId = target.getValue();
                String expectedKey = pg.transactionOf(providerId).transactionKey();
                JsonNode body = post("/api/v1/orders/" + orderId + "/payment/reconcile", null, userId, 200);
                responses.add(Map.of("orderId", orderId, "providerOrderId", providerId, "status", 200,
                    "body", mapper.convertValue(body, Map.class)));
                assertThat(body.path("orderId").asLong()).isEqualTo(orderId);
                assertThat(body.path("orderStatus").asText()).isEqualTo("CONFIRMED");
                assertThat(body.path("attemptStatus").asText()).isEqualTo("SUCCESS");
                assertThat(body.path("lookupUnavailable").asBoolean()).isFalse();
                assertThat(jdbc.queryForObject(
                    "select transaction_key from payment_attempts where provider_order_id = ?", String.class, providerId))
                    .as("same PG transaction key must be applied").isEqualTo(expectedKey);
            }

            Snapshot afterFirst = snapshot(productId);
            data.put("after", afterFirst);
            assertRecovered(afterFirst, fault, before);

            // 반복 reconcile 과 callback 재전송은 같은 업무 사실로 수렴한다.
            for (var target : fault.faultTargets().entrySet()) {
                long orderId = target.getKey();
                String providerId = target.getValue();
                JsonNode again = post("/api/v1/orders/" + orderId + "/payment/reconcile", null, userId, 200);
                assertThat(again.path("attemptStatus").asText()).isEqualTo("SUCCESS");
                var callback = new PaymentV1Dto.CallbackRequest(
                    providerId, pg.transactionOf(providerId).transactionKey(), "SUCCESS");
                assertThat(post("/api/v1/payments/callback", callback, null, 200).path("attemptStatus").asText())
                    .isEqualTo("SUCCESS");
            }
            Snapshot afterRepeat = snapshot(productId);
            data.put("afterRepeat", afterRepeat);
            assertThat(afterRepeat).isEqualTo(afterFirst);
            assertRecovered(afterRepeat, fault, before);

            // 남의 주문 조정은 기존 보호 그대로 404 다.
            long otherUserId = users.save(new User()).getId();
            long someFaultOrder = fault.faultTargets().keySet().iterator().next();
            assertThat(postStatus("/api/v1/orders/" + someFaultOrder + "/payment/reconcile", null, otherUserId))
                .isEqualTo(404);
            data.put("otherUserReconcileStatus", 404);

            long outboxAfter = count("select count(*) from order_confirmed_outbox");
            data.put("outboxAfter", outboxAfter);
            data.put("outboxEventIds", jdbc.queryForList("select event_id from order_confirmed_outbox order by id", String.class));
            assertThat(outboxAfter).isEqualTo(outboxBefore + 3).isEqualTo(20);
            assertThat(count("select count(distinct event_id) from order_confirmed_outbox")).isEqualTo(20);
            assertThat(count("select count(*) from order_confirmed_outbox where status = 'PENDING'")).isEqualTo(3);

            Map<String, List<Map<String, Object>>> sideAfter = sideState();
            data.put("sideStateAfter", sideAfter);
            assertThat(sideAfter).as("points and coupons must not change during PG recovery").isEqualTo(sideBefore);

            // 복구된 사실이 이미 공개한 r1 snapshot 을 바꾸지 않는다 (S8 의 r2 는 별도 단계).
            assertThat(count("select count(*) from weekly_ranking_inputs where snapshot_id = '" + r1.snapshotId() + "'"))
                .isEqualTo(17);
            JsonNode r1Again = getApi("/api/v1/rankings/weekly?periodStart=" + r1.periodStart());
            data.put("rankingAfterRecovery", mapper.convertValue(r1Again, Map.class));
            assertThat(r1Again.path("snapshotId").asText()).isEqualTo(r1.snapshotId());
            assertThat(r1Again.path("items").get(0).path("score").asLong()).isEqualTo(r1.score());

            HttpObservation health = getManagement("/actuator/health");
            data.put("health", health);
            assertThat(mapper.readTree(health.body()).path("status").asText()).isEqualTo("UP");
            AtomicReference<HttpObservation> prometheus = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                HttpObservation observed = getManagement("/actuator/prometheus");
                prometheus.set(observed);
                assertThat(sample(observed.body(), COUNT, "state=\"unknown\"")).isZero();
                assertThat(sample(observed.body(), COUNT, "state=\"pending\"")).isZero();
                assertThat(sample(observed.body(), COUNT, "state=\"requested\"")).isZero();
                assertThat(sample(observed.body(), OLDEST, "state=\"unknown\"")).isNaN();
            });
            data.put("prometheus", prometheus.get());

            List<BoundaryLog> captured = boundaryLogs(logs, logStart);
            for (var target : fault.faultTargets().entrySet()) {
                assertThat(captured).anyMatch(log -> log.message().startsWith(
                    "payment.reconcile orderId=" + target.getKey() + " ") && log.message().contains("decision=APPLIED"));
                assertThat(captured).anyMatch(log -> log.message().startsWith(
                    "payment.reconcile orderId=" + target.getKey() + " ") && log.message().contains("decision=ALREADY_SETTLED"));
                assertThat(captured).anyMatch(log -> log.message().startsWith(
                    "pg.lookup outcome=FOUND orderId=" + target.getValue() + " ") && log.message().contains("statuses=[SUCCESS]"));
            }
            assertThat(captured).noneMatch(log -> log.message().startsWith("pg.create "));
            data.put("result", "PASSED");
        } catch (Throwable e) {
            failure = e;
            data.put("result", e instanceof AssertionError ? "FAILED" : "ERROR");
            data.put("failureType", e.getClass().getName());
            throw e;
        } finally {
            stages.put("recovery", data.get("result"));
            data.put("endedAt", Instant.now().toString());
            data.put("logs", boundaryLogs(logs, logStart));
            try {
                if (!data.containsKey("after")) {
                    data.put("after", snapshot(productId));
                }
                evidence.write(mapper, "recovery.json", data);
            } catch (Exception writeFailure) {
                if (failure == null) {
                    throw writeFailure;
                }
                failure.addSuppressed(writeFailure);
            }
        }
    }

    /**
     * 복구 후 불변 조건. 같은 주문 집합이 20·0 이고, 거래·create·재고는 복구 전과 같아야 한다.
     */
    private static void assertRecovered(Snapshot after, Stage fault, Snapshot before) {
        assertThat(after.orders()).hasSize(20);
        assertThat(after.orders().stream().filter(row -> "SUCCESS".equals(row.get("attempt_status"))).count()).isEqualTo(20);
        assertThat(after.orders().stream().filter(row -> "UNKNOWN".equals(row.get("attempt_status"))).count()).isZero();
        assertThat(after.transactions()).isEqualTo(before.transactions()).hasSize(20)
            .allMatch(transaction -> "SUCCESS".equals(transaction.status()));
        assertThat(after.createCount()).isEqualTo(before.createCount()).isEqualTo(20);
        assertThat(after.creates()).isEqualTo(before.creates()).hasSize(20)
            .allSatisfy((providerId, creates) -> assertThat(creates).isEqualTo(1));
        assertThat(after.stock()).isEqualTo(before.stock()).isEqualTo(10);
        assertThat(after.orders()).allSatisfy(row -> {
            assertThat(row.get("order_status")).isEqualTo("CONFIRMED");
            assertThat(row.get("reservation_status")).isEqualTo("CONSUMED");
            assertThat(row.get("paid_amount")).isEqualTo(row.get("amount"));
            assertThat(row.get("confirmed_at")).isNotNull();
            assertThat(row.get("transaction_key")).isNotNull();
        });
        for (var target : fault.faultTargets().entrySet()) {
            var row = after.orders().stream()
                .filter(it -> target.getKey().equals(((Number) it.get("order_id")).longValue())).findFirst().orElseThrow();
            var transaction = after.transactions().stream()
                .filter(it -> it.providerOrderId().equals(target.getValue())).findFirst().orElseThrow();
            assertThat(row.get("transaction_key")).isEqualTo(transaction.transactionKey());
        }
    }

    /** 포인트·쿠폰은 PG 복구로 바뀌지 않아야 한다. */
    private Map<String, List<Map<String, Object>>> sideState() {
        return Map.of(
            "points", jdbc.queryForList("select * from points order by id"),
            "coupons", jdbc.queryForList("select * from coupon order by id"));
    }

    /**
     * 같은 주의 JobInstance 가 이미 있으면 식별 파라미터가 겹쳐 재시작으로 해석된다.
     * BATCH_ 메타데이터에 남은 revision 중 가장 큰 값 다음을 쓴다. 기존 메타데이터를 지우지 않는다.
     */
    private int nextRevision(LocalDate periodStart) {
        Integer max = jdbc.queryForObject("""
            select coalesce(max(cast(substring_index(parameter_value, '-r', -1) as unsigned)), 0)
            from BATCH_JOB_EXECUTION_PARAMS
            where parameter_name = 'snapshotId' and parameter_value like ?
            """, Integer.class, snapshotId(periodStart, 0).replace("-r0", "-r%"));
        return (max == null ? 0 : max) + 1;
    }

    private static String snapshotId(LocalDate periodStart, int revision) {
        return "weekly-" + periodStart.format(DateTimeFormatter.BASIC_ISO_DATE) + "-r" + revision;
    }

    private static JobParameters params(LocalDate periodStart, String snapshotId) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart.toString(), true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();
    }

    private JsonNode getApi(String path) throws IOException {
        ResponseEntity<String> response = http.getForEntity("http://127.0.0.1:" + apiPort + path, String.class);
        assertThat(response.getStatusCode().value()).as(path).isEqualTo(200);
        return mapper.readTree(response.getBody()).path("data");
    }

    private int postStatus(String path, Object body, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-USER-ID", userId.toString());
        }
        return http.postForEntity("http://127.0.0.1:" + apiPort + path,
            new HttpEntity<>(body, headers), String.class).getStatusCode().value();
    }

    private record Ranking(LocalDate periodStart, int revision, String snapshotId, long score) {}

    private Map<String, List<Map<String, Object>>> diagnosisDbState() {
        return Map.of(
            "orders", jdbc.queryForList("select * from orders order by id"),
            "attempts", jdbc.queryForList("select * from payment_attempts order by id"),
            "reservations", jdbc.queryForList("select * from stock_reservations order by id"),
            "outbox", jdbc.queryForList("select * from order_confirmed_outbox order by id"));
    }

    private Snapshot snapshot(long productId) {
        List<Map<String, Object>> orders = jdbc.queryForList("""
            select o.id as order_id, o.status as order_status, o.paid_amount, o.confirmed_at,
                   p.id as attempt_id, p.attempt_key, p.provider_order_id, p.transaction_key,
                   p.status as attempt_status, p.amount, p.created_at, p.updated_at, p.lookup_count,
                   r.id as reservation_id, r.status as reservation_status, r.quantity as reservation_quantity
            from orders o left join payment_attempts p on p.order_id = o.id
            left join stock_reservations r on r.order_id = o.id
            order by o.id
            """);
        List<TestPaymentPgServer.StoredTransaction> transactions = pg.storedTransactions();
        Map<String, Integer> creates = new LinkedHashMap<>();
        Map<String, Long> lookups = new LinkedHashMap<>();
        for (var transaction : transactions) {
            String providerId = transaction.providerOrderId();
            creates.put(providerId, pg.createCount(providerId));
            lookups.put(providerId, pg.receivedRequests().stream()
                .filter(request -> "GET".equals(request.method()) && providerId.equals(request.query().get("orderId"))).count());
        }
        return new Snapshot(orders, transactions, creates, lookups, pg.createCount(),
            products.findById(productId).orElseThrow().getStock().getQuantity());
    }

    private static void assertState(Snapshot snapshot, List<Long> ids, Map<Long, String> targets) {
        int total = targets.isEmpty() ? 10 : 20;
        assertThat(snapshot.orders()).hasSize(total);
        assertThat(snapshot.transactions()).hasSize(total).allMatch(tx -> "SUCCESS".equals(tx.status()));
        assertThat(snapshot.createCount()).isEqualTo(total);
        assertThat(snapshot.creates()).hasSize(total).allSatisfy((id, count) -> assertThat(count).isEqualTo(1));
        assertThat(snapshot.orders().stream().filter(row -> "SUCCESS".equals(row.get("attempt_status"))).count())
            .isEqualTo(total - targets.size());
        assertThat(snapshot.orders().stream().filter(row -> "UNKNOWN".equals(row.get("attempt_status"))).count())
            .isEqualTo(targets.size());
        assertThat(snapshot.orders().stream().filter(row -> ids.contains(row.get("order_id"))).toList())
            .hasSize(10).allSatisfy(row -> {
                long id = ((Number) row.get("order_id")).longValue();
                String providerId = PaymentAttempt.providerOrderIdOf(id);
                boolean fault = targets.containsKey(id);
                var transaction = snapshot.transactions().stream().filter(tx -> tx.providerOrderId().equals(providerId)).findFirst().orElseThrow();
                assertThat(row.get("provider_order_id")).isEqualTo(providerId);
                assertThat(row.get("attempt_status")).isEqualTo(fault ? "UNKNOWN" : "SUCCESS");
                assertThat(row.get("order_status")).isEqualTo(fault ? "PAYMENT_PENDING" : "CONFIRMED");
                assertThat(row.get("reservation_status")).isEqualTo(fault ? "RESERVED" : "CONSUMED");
                assertThat(row.get("reservation_quantity")).isEqualTo(1);
                assertThat(row.get("transaction_key")).isEqualTo(fault ? null : transaction.transactionKey());
                assertThat(row.get("amount")).isEqualTo(transaction.amount());
                assertThat(row.get("paid_amount")).isEqualTo(fault ? 0L : transaction.amount());
                assertThat(row.get("lookup_count")).isEqualTo(0);
                assertThat(snapshot.lookups().get(providerId)).isEqualTo(fault ? 0L : 1L);
            });
    }

    private JsonNode post(String path, Object body, Long userId, int expected) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-USER-ID", userId.toString());
        }
        ResponseEntity<String> response = http.postForEntity("http://127.0.0.1:" + apiPort + path,
            new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().value()).as(path).isEqualTo(expected);
        return mapper.readTree(response.getBody()).path("data");
    }

    private HttpObservation getManagement(String path) {
        ResponseEntity<String> response = http.getForEntity("http://127.0.0.1:" + managementPort + path, String.class);
        return new HttpObservation(response.getStatusCode().value(), response.getBody(), System.currentTimeMillis());
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static double sample(String body, String name, String label) {
        if (body == null) {
            return Double.NaN;
        }
        return body.lines().filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
            .filter(line -> label == null || line.contains(label))
            .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1))).findFirst().orElse(Double.NaN);
    }

    private static List<BoundaryLog> boundaryLogs(ListAppender<ILoggingEvent> logs, int start) {
        return List.copyOf(logs.list).stream().skip(start)
            .filter(event -> event.getMessage().contains(" ")
                && BOUNDARIES.contains(event.getMessage().substring(0, event.getMessage().indexOf(' '))))
            .map(event -> new BoundaryLog(Instant.ofEpochMilli(event.getTimeStamp()).toString(),
                event.getLevel().toString(), event.getFormattedMessage()))
            .filter(event -> !event.message().contains(CARD)).toList();
    }

    private record Snapshot(
        List<Map<String, Object>> orders, List<TestPaymentPgServer.StoredTransaction> transactions,
        Map<String, Integer> creates, Map<String, Long> lookups, long createCount, int stock
    ) {}

    private record HttpObservation(int status, String body, long observedAtEpochMillis) {}
    private record Observation(HttpObservation prometheus, double lastSuccess, double ageSeconds, double unknown, double successCounter) {}
    private record BoundaryLog(String loggedAt, String level, String message) {}
    private record Stage(List<Long> orderIds, Map<Long, String> faultTargets, Snapshot snapshot, double lastSuccess) {}
}
