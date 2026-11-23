package com.loopers.infrastructure.order;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderOutboxExecutionGate;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelay.RowOutcome;
import com.loopers.application.order.OrderOutboxRelayTransactionService;
import com.loopers.application.payment.PaymentTxDiagnostics;
import com.loopers.application.point.PointFacade;
import com.loopers.config.OrderConfirmationClockConfig;
import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.config.OrderOutboxRelayProperties;
import com.loopers.domain.order.OrderOutboxMessageVerifier;
import com.loopers.domain.order.OrderOutboxReceipt;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.fixture.CouponFixture;
import com.loopers.fixture.PgCommands;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.SettableClock;
import com.loopers.fixture.UserFixture;
import com.loopers.fixture.pg.CallbackBatchResult;
import com.loopers.fixture.pg.FixtureState;
import com.loopers.fixture.pg.JournalEntry;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponseFailure;
import com.loopers.fixture.pg.ResponsePlan;
import com.loopers.fixture.pg.TransactionSnapshot;
import com.loopers.infrastructure.order.IncidentEvidenceWriter.StageSnapshot;
import com.loopers.infrastructure.order.OrderOutboxE2eLifecycleSupport.ChildLaunch;
import com.loopers.infrastructure.order.OrderOutboxE2eLifecycleSupport.CleanupSteps;
import com.loopers.infrastructure.order.OrderOutboxE2eLifecycleSupport.Ownership;
import com.loopers.infrastructure.payment.PgHttpGateway;
import com.loopers.interfaces.api.ranking.WeeklyRankingSchemaSupport;
import com.loopers.pgsimulator.PaymentStatus;
import com.loopers.testsupport.LocalKafkaTestSupport;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * W10 공통 20주문 사고 시나리오(checkout-incident §2·§5·§6·§8). AUTO·OBSERVED 가 같은 업무 assertion 을 공유한다.
 * baseline→fault→recovery 사이에 DB·PG 초기화를 하지 않는다.
 */
final class CheckoutIncidentScenario {

    static final Instant CONFIRMED_AT = Instant.parse("2026-11-23T01:00:00Z");

    private static final String LABEL = "CHECKOUT_INCIDENT";
    private static final Duration OBSERVE = Duration.ofSeconds(20);
    private static final Duration CHILD = Duration.ofSeconds(60);
    private static final Duration BATCH = Duration.ofSeconds(120);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);
    /** 업무 API 는 PG 조회·TX 를 포함해 응답이 길 수 있어 읽기 대기를 넉넉히 둔다. */
    private static final Duration BUSINESS_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Path LOG_DIR = Path.of("build", "checkout-incident-children");
    private static final int COHORT = 10;
    private static final int UNKNOWN_COUNT = 3;
    private static final long PRICE_A = 1_000L;
    private static final long PRICE_B = 2_000L;
    private static final long START_STOCK = 100L;
    private static final long ORDER_AMOUNT = 4_000L;
    private static final long POINT_CHARGE = 10_000L;
    private static final String WEEK = "2026-11-23";
    private static final String SNAPSHOT = "weekly-20261123-r1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SettableClock confirmationClock = new SettableClock(CONFIRMED_AT, ZoneOffset.UTC);
    private final Ownership ownership = new Ownership();
    private final List<PlanRegistration> planLedger = new ArrayList<>();
    private final List<String> deliveries = new ArrayList<>();
    private final ListAppender<ILoggingEvent> diagnostics = new ListAppender<>();
    private final RestTemplate http = client(BUSINESS_READ_TIMEOUT);
    /** health·Prometheus 직접 GET 전용. connect/read 각각 3초이며 호출 전체의 엄밀한 상한은 아니다. */
    private final RestTemplate observer = client(HTTP_TIMEOUT);

    private IncidentEvidenceWriter writer;
    private IncidentDeadline deadline;
    private IncidentStage stage;
    private LocalKafkaTestSupport kafka;
    private PgFixtureServer pg;
    private ConfigurableApplicationContext api;
    private OrderActivityStreamerChild streamer;
    private JdbcTemplate fresh;
    private String topic;
    private String group;
    private int managementPort;
    private Roster roster;
    private Map<String, Object> preservedBefore;
    private String expectationsSha256;

    record ScenarioInputs(String operator) {
    }

    /** 각 주문의 실제 식별자. cohort 는 BASELINE 또는 FAULT, unknown 은 F 생성 순서 첫 3개다. */
    record RosterEntry(long orderId, String cohort, boolean unknown, String attemptKey, String providerOrderId) {
    }

    record Roster(long userId, long otherUserId, long productA, long productB, List<RosterEntry> entries) {

        List<Long> baseline() {
            return ids("BASELINE", false);
        }

        List<Long> fault() {
            return ids("FAULT", false);
        }

        List<Long> unknowns() {
            return entries.stream().filter(RosterEntry::unknown).map(RosterEntry::orderId).toList();
        }

        private List<Long> ids(String cohort, boolean onlyUnknown) {
            return entries.stream().filter(e -> e.cohort().equals(cohort) && (!onlyUnknown || e.unknown()))
                .map(RosterEntry::orderId).toList();
        }
    }

    /** 시나리오의 유일한 plan 등록 경로(§5). 등록 실패는 재등록하지 않고 실행 실패다. */
    record PlanRegistration(int sequence, IncidentStage stage, String operation, String target, String plan, boolean succeeded) {
    }

    void run(IncidentMode mode, ScenarioInputs inputs, IncidentEvidenceWriter evidence, IncidentCheckpoint checkpoint,
             IncidentDeadline runDeadline) throws Exception {
        this.writer = evidence;
        this.deadline = runDeadline;
        runThenRelease(() -> {
            stage = null;
            prepare();
            stage = IncidentStage.BASELINE;
            baseline(checkpoint);
            stage = IncidentStage.FAULT;
            fault(checkpoint);
            stage = IncidentStage.RECOVERY;
            recovery(checkpoint);
        }, failure -> {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("mode", mode.name());
            context.put("operator", inputs.operator());
            if (failure instanceof AssertionError || failure instanceof IllegalStateException) {
                context.put("message", failure.getMessage());
            }
            writer.recordFailureKeeping(failure, stage, context);
        }, this::release);
    }

    @FunctionalInterface
    interface Body {
        void run() throws Exception;
    }

    /**
     * 업무 실패를 주 예외로 두고 정리 실패는 suppressed 로 붙인다. 업무가 성공했으면 정리 실패를 그대로 던져 통과로 삼키지 않는다.
     * 실패 기록이 실패해도 원래 오류를 잃지 않는다.
     */
    static void runThenRelease(Body body, Consumer<Throwable> recordFailure, Runnable release) throws Exception {
        Throwable primary = null;
        try {
            body.run();
        } catch (Throwable failure) {
            primary = failure;
            try {
                recordFailure.accept(failure);
            } catch (RuntimeException recordError) {
                failure.addSuppressed(recordError);
            }
            throw failure;
        } finally {
            try {
                release.run();
            } catch (RuntimeException | Error cleanupFailure) {
                if (primary == null) {
                    throw cleanupFailure;
                }
                primary.addSuppressed(cleanupFailure);
            }
        }
    }

    // ---------------------------------------------------------------- 준비(§6.2)

    private void prepare() throws Exception {
        kafka = LocalKafkaTestSupport.connect();
        topic = kafka.createTopic("checkout-incident");
        group = kafka.ownGroup("purchase-activity-incident");
        writer.recordResource("kafka", Map.of("runId", kafka.runId(), "topic", topic, "group", group));
        pg = PgFixtureServer.start();
        writer.recordResource("pg", Map.of("baseUrl", pg.baseUrl(), "settlement", "DISABLED"));
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        Class.forName("com.loopers.testcontainers.RedisTestContainersConfig");
        String jdbcUrl = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        fresh = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl,
            System.getProperty("datasource.mysql-jpa.main.username"), System.getProperty("datasource.mysql-jpa.main.password")));
        writer.recordResource("db", Map.of("jdbcUrl", jdbcUrl, "owner", "testcontainers-mysql"));
        bootApi();
        attachDiagnostics();
        startStreamer();
        long instancesBefore = weeklyRankingInstances();
        WeeklyRankingBatchChild.Result prepared = WeeklyRankingBatchChild.prepareSchema(LOG_DIR, limit(BATCH));
        assertThat(prepared.exitCode()).as("batch schema 준비 child").isZero();
        assertThat(weeklyRankingInstances()).as("준비 child 는 업무 Job 을 실행하지 않음").isEqualTo(instancesBefore);
        WeeklyRankingSchemaSupport.assertDefinitions(fresh);
        writer.recordResource("batch-schema-child", Map.of("logDir", LOG_DIR.toString(), "exitCode", prepared.exitCode()));

        roster = prepareData();
        preservedBefore = preserved();
        writer.writeStage(IncidentStage.BASELINE, StageSnapshot.of(
            new StageSnapshot.Json("roster", roster),
            new StageSnapshot.Json("preserved-before", preservedBefore)));
        expectationsSha256 = writer.writeExpectations(IncidentDiagnosis.expectations());
    }

    /** 사용자·상품·포인트·쿠폰을 준비하고 B10·F10 주문을 실제 API 로 만든다. 어떤 confirm 도 하기 전에 명단을 고정한다. */
    private Roster prepareData() {
        ProductFixture products = api.getBean(ProductFixture.class);
        long productA = products.createProduct("사고 상품 A", PRICE_A, START_STOCK).getId();
        long productB = products.createProduct("사고 상품 B", PRICE_B, START_STOCK).getId();
        UserFixture users = api.getBean(UserFixture.class);
        long user = users.createUserWithPoint().getId();
        long other = users.createUserWithPoint().getId();
        api.getBean(PointFacade.class).charge(user, POINT_CHARGE);
        CouponFixture coupons = api.getBean(CouponFixture.class);
        coupons.storedCoupon(user, coupons.activeCampaign(1));

        List<RosterEntry> entries = new ArrayList<>();
        for (String cohort : List.of("BASELINE", "FAULT")) {
            for (int i = 0; i < COHORT; i++) {
                ResponseEntity<JsonNode> created = post(user, "/api/v1/orders", Map.of("items", List.of(
                    Map.of("productId", productA, "quantity", 2), Map.of("productId", productB, "quantity", 1))));
                assertThat(created.getStatusCode().value()).as("주문 생성").isEqualTo(201);
                long orderId = created.getBody().path("data").path("id").asLong();
                assertThat(created.getBody().path("data").path("totalAmount").asLong()).isEqualTo(ORDER_AMOUNT);
                entries.add(new RosterEntry(orderId, cohort, cohort.equals("FAULT") && i < UNKNOWN_COUNT,
                    PgCommands.forOrder(orderId).paymentAttemptKey(), PaymentModel.providerOrderIdOf(orderId)));
            }
        }
        assertThat(entries.stream().map(RosterEntry::orderId).distinct()).as("B·F 주문 ID 겹침 없음").hasSize(2 * COHORT);
        return new Roster(user, other, productA, productB, entries);
    }

    // ---------------------------------------------------------------- BASELINE(§8.1)

    private void baseline(IncidentCheckpoint checkpoint) {
        assertThat(successfulRegistrations()).as("baseline 등록 plan 0").isZero();
        for (long orderId : roster.baseline()) {
            confirmPendingThenReconcile(orderId);
        }
        relayAndAwait(roster.baseline());

        Map<String, Object> summary = assertDatabase(roster.baseline(), 10, 0, 10, 10, 80, 90, 10, 20, 20, 10);
        assertOrderRows(summary, roster.baseline(), roster.baseline());
        assertThat(preserved()).as("포인트·쿠폰 불변").isEqualTo(preservedBefore);
        String prometheus = assertDirectObservation(0);
        writeStageEvidence(IncidentStage.BASELINE, summary, prometheus);
        checkpoint.await(IncidentStage.BASELINE, new IncidentCheckpoint.Facts("baseline", summary, null), deadline);
    }

    /** 기본 PENDING 생성 → PG 거래 SUCCESS 변경(fixture 제어) → 본인 reconcile 로 확정한다. commerce DB 를 직접 바꾸지 않는다. */
    private void confirmPendingThenReconcile(long orderId) {
        ResponseEntity<JsonNode> confirmed = post(roster.userId(), "/api/v1/orders/" + orderId + "/confirm",
            PgCommands.forOrder(orderId));
        assertThat(confirmed.getStatusCode().value()).as("confirm PENDING 응답 " + orderId).isEqualTo(202);
        assertThat(confirmed.getBody().path("data").path("status").asText()).isEqualTo("PAYMENT_PENDING");
        awaitPgIdle();
        TransactionSnapshot transaction = singleTransaction(PaymentModel.providerOrderIdOf(orderId));
        assertThat(transaction.status()).isEqualTo(PaymentStatus.PENDING);
        pg.setStatus(transaction.transactionKey(), PaymentStatus.SUCCESS);
        ResponseEntity<JsonNode> reconciled = post(roster.userId(), "/api/v1/orders/" + orderId + "/payment/reconcile", null);
        assertThat(reconciled.getStatusCode().value()).as("reconcile 확정 " + orderId).isEqualTo(200);
        assertThat(reconciled.getBody().path("data").path("status").asText()).isEqualTo("CONFIRMED");
        awaitPgIdle();
    }

    // ---------------------------------------------------------------- FAULT(§5·§8.1)

    /**
     * 정상 F7 은 baseline 과 같은 경로, U3 는 SUCCESS 저장 후 생성 응답 유실 plan 하나로 confirm 한다. U 의 callback·reconcile 은 하지 않는다.
     * 수집한 증거로 자동 판정을 만든 뒤 fault 원문을 봉인한다. 판정이 진행을 허용하지 않으면 복구로 넘어가지 않는다.
     */
    private void fault(IncidentCheckpoint checkpoint) {
        int windowStart = diagnosticLines().size();
        List<Long> unknowns = roster.unknowns();
        List<Long> normal = roster.fault().stream().filter(id -> !unknowns.contains(id)).toList();
        for (long orderId : normal) {
            confirmPendingThenReconcile(orderId);
        }
        for (long orderId : unknowns) {
            confirmWithLostResponse(orderId);
        }
        assertInjectionConsumed(unknowns);
        relayAndAwait(normal);
        assertThat(count("SELECT COUNT(*) FROM order_confirmed_outbox WHERE order_id IN (" + in(unknowns) + ")"))
            .as("U Outbox 0").isZero();
        assertThat(count("SELECT COUNT(*) FROM product_purchase_activity WHERE order_id IN (" + in(unknowns) + ")"))
            .as("U 활동 0").isZero();

        Map<String, Object> summary = assertDatabase(roster.fault(), 7, 3, 10, 20, 60, 80, 17, 34, 34, 17);
        assertOrderRows(summary, concat(roster.baseline(), normal), concat(roster.baseline(), roster.fault()));
        assertThat(preserved()).as("포인트·쿠폰 불변").isEqualTo(preservedBefore);
        String direct = assertDirectObservation(UNKNOWN_COUNT);
        writeStageEvidence(IncidentStage.FAULT, summary, direct);

        IncidentDiagnosis.Result diagnosis = diagnose(unknowns, windowStart);
        writer.writeStage(IncidentStage.FAULT, StageSnapshot.of(new StageSnapshot.Json("diagnosis", diagnosis)));
        assertThat(diagnosis.proceed()).as("자동 판정이 복구 진행을 허용해야 함: " + diagnosis).isTrue();
        writer.sealFault();
        String diagnosisSha256 = IncidentCodeReference.sha256(readEvidence("fault/diagnosis.json"));
        checkpoint.await(IncidentStage.FAULT, new IncidentCheckpoint.Facts("fault", summary,
            new IncidentCheckpoint.FaultFacts("fault/diagnosis.json", diagnosisSha256, unknowns, diagnosis.proceed())), deadline);
    }

    /** 등록 helper 로 한 요청짜리 plan 을 건 뒤 실제 confirm 한다. 응답 유실이라 결제는 UNKNOWN·주문은 PAYMENT_PENDING 이다. */
    private void confirmWithLostResponse(long orderId) {
        registerPlan(PgOperation.CREATE, PaymentModel.providerOrderIdOf(orderId), "SUCCESS_THEN_CLOSE_EARLY",
            ResponsePlan.successThenCloseEarly());
        ResponseEntity<JsonNode> confirmed = post(roster.userId(), "/api/v1/orders/" + orderId + "/confirm",
            PgCommands.forOrder(orderId));
        assertThat(confirmed.getStatusCode().value()).as("U confirm 결과 미확인 응답 " + orderId).isEqualTo(202);
        assertThat(confirmed.getBody().path("data").path("status").asText()).isEqualTo("PAYMENT_PENDING");
        awaitPgIdle();
        assertThat(fresh.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, orderId))
            .as("U 결제 UNKNOWN " + orderId).isEqualTo("UNKNOWN");
    }

    /**
     * §5: 이번 실행의 성공 등록은 U 세 대상의 CREATE 각 1개뿐이고, 각 대상의 실제 CREATE 1개가 같은 SUCCESS 거래로 CLOSED_EARLY 됐다.
     * 진행 작업 0 은 남은 plan 수 0 이 아니다. 등록 3·세 대상 소비로 이번 주입의 소진을 판단한다.
     */
    private void assertInjectionConsumed(List<Long> unknowns) {
        List<PlanRegistration> registered = planLedger.stream().filter(PlanRegistration::succeeded).toList();
        assertThat(registered).as("성공 등록 3개·U 대상").extracting(PlanRegistration::stage, PlanRegistration::operation,
                PlanRegistration::plan)
            .containsOnly(tuple(IncidentStage.FAULT, "CREATE", "SUCCESS_THEN_CLOSE_EARLY"));
        assertThat(registered).extracting(PlanRegistration::target)
            .containsExactlyElementsOf(unknowns.stream().map(PaymentModel::providerOrderIdOf).toList());
        FixtureState state = pg.state();
        assertThat(state.activeRequests()).isZero();
        assertThat(state.activeCallbackJobs()).isZero();
        assertThat(state.gates()).as("미해제 gate 없음").isEmpty();
        List<JournalEntry> creates = state.journal().stream().filter(e -> e.operation() == PgOperation.CREATE).toList();
        assertThat(creates).as("전체 CREATE").hasSize(2 * COHORT);
        Map<String, Long> perTarget = creates.stream().collect(Collectors.groupingBy(JournalEntry::target, Collectors.counting()));
        assertThat(perTarget).as("주문별 CREATE 1").hasSize(2 * COHORT).allSatisfy((target, n) -> assertThat(n).isEqualTo(1L));
        for (long orderId : unknowns) {
            String providerOrderId = PaymentModel.providerOrderIdOf(orderId);
            JournalEntry entry = creates.stream().filter(e -> e.target().equals(providerOrderId)).findFirst().orElseThrow();
            TransactionSnapshot transaction = singleTransaction(providerOrderId);
            assertThat(entry.failure()).as("U CLOSED_EARLY " + orderId).isEqualTo(ResponseFailure.CLOSED_EARLY);
            assertThat(entry.responseStatus()).isEqualTo(200);
            assertThat(entry.createdTransactionKey()).isEqualTo(transaction.transactionKey());
            assertThat(transaction.status()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(transaction.amount()).isEqualTo(ORDER_AMOUNT);
        }
    }

    /**
     * 자동 판정 입력을 모은다. 권위 조회는 기존 계정으로 proxy baseUrl 을 거친 PG 주문별 GET 이며 commerce 결과 적용을 호출하지 않는다.
     * 조회 뒤 commerce 상태가 그대로인지 새 DB 조회로 확인한다.
     */
    private IncidentDiagnosis.Result diagnose(List<Long> unknowns, int windowStart) {
        List<String> window = diagnosticLines().subList(windowStart, diagnosticLines().size());
        List<JsonNode> events = window.stream().map(CheckoutIncidentScenario::event).toList();
        FixtureState before = pg.state();
        Map<Long, IncidentDiagnosis.Db> dbBefore = new LinkedHashMap<>();
        Map<Long, Integer> callsBefore = new LinkedHashMap<>();
        for (long orderId : unknowns) {
            dbBefore.put(orderId, unknownDb(orderId));
            String providerOrderId = PaymentModel.providerOrderIdOf(orderId);
            String key = singleTransaction(providerOrderId).transactionKey();
            long lookups = before.journal().stream().filter(e -> e.operation() != PgOperation.CREATE
                && (providerOrderId.equals(e.target()) || key.equals(e.target()))).count();
            callsBefore.put(orderId, (int) (lookups + before.counts().callbackSent()));
        }
        Map<String, Object> rawLookups = new LinkedHashMap<>();
        Map<Long, IncidentDiagnosis.Pg> authoritative = new LinkedHashMap<>();
        String account = api.getEnvironment().getProperty("commerce.pg.account-id");
        for (long orderId : unknowns) {
            String providerOrderId = PaymentModel.providerOrderIdOf(orderId);
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-USER-ID", account);
            ResponseEntity<JsonNode> response = http.exchange(pg.baseUrl() + "/api/v1/payments?orderId=" + providerOrderId,
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            rawLookups.put(providerOrderId, Map.of("status", response.getStatusCode().value(),
                "body", String.valueOf(response.getBody())));
            List<IncidentDiagnosis.PgTransaction> transactions = new ArrayList<>();
            boolean ok = response.getStatusCode().value() == 200 && response.getBody() != null && response.getBody().isArray();
            if (ok) {
                response.getBody().forEach(t -> transactions.add(new IncidentDiagnosis.PgTransaction(t.path("transactionKey").asText(),
                    t.path("orderId").asText(), t.path("amount").asLong(), t.path("status").asText())));
            }
            authoritative.put(orderId, new IncidentDiagnosis.Pg(ok, transactions));
        }
        awaitPgIdle();
        writer.writeStage(IncidentStage.FAULT, StageSnapshot.of(new StageSnapshot.Json("u-lookups", rawLookups)));
        List<IncidentDiagnosis.UnknownEvidence> evidence = new ArrayList<>();
        for (long orderId : unknowns) {
            IncidentDiagnosis.Db after = unknownDb(orderId);
            assertThat(after).as("진단 조회 뒤 commerce 상태 불변 " + orderId).isEqualTo(dbBefore.get(orderId));
            String providerOrderId = PaymentModel.providerOrderIdOf(orderId);
            List<JournalEntry> createJournal = before.journal().stream()
                .filter(e -> e.operation() == PgOperation.CREATE && providerOrderId.equals(e.target())).toList();
            evidence.add(new IncidentDiagnosis.UnknownEvidence(orderId, providerOrderId, ORDER_AMOUNT, authoritative.get(orderId),
                createJournal, events, after, callsBefore.get(orderId), List.of("fault/summary.json", "fault/orders.json",
                "fault/pg-state.json", "fault/diagnostic-log.txt", "fault/u-lookups.json")));
        }
        return IncidentDiagnosis.judge(expectationsSha256, evidence);
    }

    private IncidentDiagnosis.Db unknownDb(long orderId) {
        return new IncidentDiagnosis.Db(
            fresh.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, orderId),
            fresh.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId),
            count("SELECT COUNT(*) FROM stock_history WHERE order_id = " + orderId + " AND cause = 'ORDER_RESERVATION'"),
            count("SELECT COUNT(*) FROM stock_history WHERE order_id = " + orderId + " AND cause = 'ORDER_RELEASE'"),
            count("SELECT COUNT(*) FROM order_confirmed_outbox WHERE order_id = " + orderId));
    }

    private static JsonNode event(String line) {
        try {
            return JSON.readTree(line.substring(line.indexOf(' ') + 1));
        } catch (IOException e) {
            throw new IllegalStateException("진단 로그 형식 오류", e);
        }
    }

    private byte[] readEvidence(String relative) {
        try {
            return Files.readAllBytes(writer.runDir().resolve(relative));
        } catch (IOException e) {
            throw new IllegalStateException("증거 파일 읽기 실패: " + relative, e);
        }
    }

    // ---------------------------------------------------------------- RECOVERY(§8.3·§8.4)

    /**
     * 봉인 불변과 주입 소진을 확인한 뒤 같은 U 를 본인 reconcile 로만 복구한다. 자동 retry·새 create·DB 직접 수정은 없다.
     * 반복 reconcile·callback·다른 사용자 시도에도 효과가 늘지 않는지 보고, U 를 실제로 전달해 활동·랭킹까지 확인한다.
     */
    private void recovery(IncidentCheckpoint checkpoint) throws Exception {
        writer.verifyFaultUnchanged();
        List<Long> unknowns = roster.unknowns();
        long registered = successfulRegistrations();
        FixtureState start = pg.state();
        assertThat(start.activeRequests()).isZero();
        assertThat(start.activeCallbackJobs()).isZero();
        assertThat(start.gates()).isEmpty();
        long journalStart = start.journal().stream().mapToLong(JournalEntry::sequence).max().orElse(0);
        long createStart = start.counts().createReceived();
        assertThat(createStart).as("복구 시작 CREATE").isEqualTo(2L * COHORT);

        for (long orderId : unknowns) {
            reconcileOwn(orderId);
        }
        Map<Long, String> boundKeys = new LinkedHashMap<>();
        for (long orderId : unknowns) {
            String key = singleTransaction(PaymentModel.providerOrderIdOf(orderId)).transactionKey();
            boundKeys.put(orderId, key);
            Map<String, Object> payment = fresh.queryForMap(
                "SELECT p.status, p.transaction_key, o.status AS order_status, o.paid_amount FROM payment p "
                    + "JOIN orders o ON o.id = p.order_id WHERE p.order_id = ?", orderId);
            assertThat(payment).as("U 복구 " + orderId).containsEntry("status", "SUCCESS").containsEntry("transaction_key", key)
                .containsEntry("order_status", "CONFIRMED").containsEntry("paid_amount", ORDER_AMOUNT);
        }
        List<JournalEntry> afterRecovery = journalAfter(journalStart);
        assertThat(afterRecovery).as("복구 중 새 CREATE 0").noneMatch(e -> e.operation() == PgOperation.CREATE);
        assertThat(afterRecovery).as("U 별 주문 조회 1회").extracting(JournalEntry::operation, JournalEntry::target)
            .containsExactlyElementsOf(unknowns.stream().map(id -> tuple(PgOperation.LOOKUP_ORDER,
                PaymentModel.providerOrderIdOf(id))).toList());

        // 반복 적용: reconcile 1회 추가와 callback 2회 명시 전송
        for (long orderId : unknowns) {
            reconcileOwn(orderId);
            CallbackBatchResult callbacks = pg.sendCallbacks(boundKeys.get(orderId), 2)
                .toCompletableFuture().get(limit(OBSERVE).toMillis(), TimeUnit.MILLISECONDS);
            assertThat(callbacks.deliveries()).as("callback ACK " + orderId).hasSize(2)
                .allSatisfy(d -> assertThat(d.sent() && Integer.valueOf(200).equals(d.httpStatus())).isTrue());
        }
        awaitPgIdle();

        // 소유권: 다른 사용자의 조회·reconcile 은 기존 404 이고 PG 호출·저장 효과가 없다
        int journalBeforeOther = pg.state().journal().size();
        List<Map<String, Object>> ordersBeforeOther = orders(unknowns);
        for (long orderId : unknowns) {
            ResponseEntity<String> read = asUser(roster.otherUserId(), HttpMethod.GET, "/api/v1/orders/" + orderId);
            ResponseEntity<String> reconcile = asUser(roster.otherUserId(), HttpMethod.POST,
                "/api/v1/orders/" + orderId + "/payment/reconcile");
            assertThat(List.of(read.getStatusCode().value(), reconcile.getStatusCode().value())).containsOnly(404);
            assertThat(read.getBody()).contains("ORDER_NOT_FOUND");
            assertThat(reconcile.getBody()).contains("ORDER_NOT_FOUND");
        }
        assertThat(pg.state().journal()).as("다른 사용자 시도에 PG 호출 없음").hasSize(journalBeforeOther);
        assertThat(orders(unknowns)).as("다른 사용자 시도에 저장 효과 없음").isEqualTo(ordersBeforeOther);

        // 후속: U 전달, 같은 사실 한 건 재발행(중복 처리), 원문 입력 불변
        relayAndAwait(unknowns);
        long firstUnknown = unknowns.get(0);
        RecordMetadata republished = republish(firstUnknown);
        awaitHandled("order-confirmed:" + firstUnknown, republished.partition(), republished.offset(), "ALREADY_PROCESSED");

        Map<String, Object> summary = assertDatabase(roster.fault(), 10, 0, 10, 20, 60, 80, 20, 40, 40, 20);
        List<Long> all = concat(roster.baseline(), roster.fault());
        assertOrderRows(summary, all, all);
        assertThat(pg.state().counts().createReceived()).as("복구 후 create 추가 0").isEqualTo(createStart);
        assertThat(successfulRegistrations()).as("복구 중 새 plan 등록 0").isEqualTo(registered);
        assertThat(count("SELECT COUNT(*) FROM order_confirmed_outbox WHERE order_id IN (" + in(unknowns) + ")"))
            .as("U Outbox 사실 각 1").isEqualTo(UNKNOWN_COUNT);
        assertThat(preserved()).as("포인트·쿠폰 불변").isEqualTo(preservedBefore);
        Map<String, Object> ranking = assertRanking();
        String direct = assertDirectObservation(0);
        writeStageEvidence(IncidentStage.RECOVERY, summary, direct);
        writer.writeStage(IncidentStage.RECOVERY, StageSnapshot.of(new StageSnapshot.Json("ranking", ranking)));
        writer.verifyFaultUnchanged();
        checkpoint.await(IncidentStage.RECOVERY, new IncidentCheckpoint.Facts("recovery", summary, null), deadline);
    }

    private void reconcileOwn(long orderId) {
        ResponseEntity<JsonNode> reconciled = post(roster.userId(), "/api/v1/orders/" + orderId + "/payment/reconcile", null);
        assertThat(reconciled.getStatusCode().value()).as("본인 reconcile " + orderId).isEqualTo(200);
        assertThat(reconciled.getBody().path("data").path("status").asText()).isEqualTo("CONFIRMED");
        awaitPgIdle();
    }

    private List<JournalEntry> journalAfter(long sequence) {
        return pg.state().journal().stream().filter(e -> e.sequence() > sequence)
            .sorted(Comparator.comparingLong(JournalEntry::sequence)).toList();
    }

    /** 새 snapshot 하나를 실제 batch 자식으로 만들고 manifest·결과·API 를 확인한다. 기존 공개본은 없다. */
    private Map<String, Object> assertRanking() throws Exception {
        assertThat(ranking().path("status").asText()).as("공개 전").isEqualTo("NOT_PUBLISHED");
        WeeklyRankingBatchChild.Result result = WeeklyRankingBatchChild.startNew(LOG_DIR, WEEK, null, limit(BATCH));
        assertThat(List.of(result.exitCode(), result.status(), result.publicationOutcome()))
            .isEqualTo(List.of(0, "COMPLETED", "PUBLISHED"));
        Map<String, Object> manifest = fresh.queryForMap(
            "SELECT status, input_count FROM ranking_snapshot WHERE snapshot_id = ?", SNAPSHOT);
        List<String> results = fresh.query("SELECT product_id, score, calculated_rank FROM ranking_result WHERE snapshot_id = ? "
                + "ORDER BY calculated_rank",
            (rs, n) -> rs.getLong(1) + ":" + rs.getBigDecimal(2).toBigIntegerExact() + ":" + rs.getLong(3), SNAPSHOT);
        List<String> expected = List.of(roster.productA() + ":40:1", roster.productB() + ":20:2");
        assertThat(manifest).containsEntry("status", "COMPLETED").containsEntry("input_count", 40L);
        assertThat(results).isEqualTo(expected);
        JsonNode api = ranking();
        List<String> items = new ArrayList<>();
        api.path("items").forEach(item -> items.add(item.path("productId").asLong() + ":" + item.path("score").asText() + ":"
            + item.path("rank").asLong()));
        assertThat(api.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(api.path("snapshotId").asText()).isEqualTo(SNAPSHOT);
        assertThat(items).isEqualTo(expected);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("instanceId", result.instanceId());
        evidence.put("executionId", result.executionId());
        evidence.put("status", result.status());
        evidence.put("publicationOutcome", result.publicationOutcome());
        evidence.put("snapshotId", SNAPSHOT);
        evidence.put("inputCount", manifest.get("input_count"));
        evidence.put("results", results);
        evidence.put("apiItems", items);
        return evidence;
    }

    private JsonNode ranking() {
        ResponseEntity<JsonNode> response = http.exchange(apiUrl() + "/api/v1/rankings/weekly?periodStart=" + WEEK,
            HttpMethod.GET, new HttpEntity<>(userHeaders(roster.userId())), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().path("data");
    }

    // ---------------------------------------------------------------- 전달(§8.4)

    /** eventId 별로 실제 relay 를 한 번 실행해 PUBLISHED·broker 위치를 얻고 streamer 처리·group commit 을 기다린다. */
    private void relayAndAwait(List<Long> orderIds) {
        List<OrderOutboxReceipt> receipts = new ArrayList<>();
        OrderOutboxRelay relay = new OrderOutboxRelay(api.getBean(OrderOutboxRelayTransactionService.class),
            api.getBean(OrderOutboxMessageVerifier.class), api.getBean(OrderOutboxSender.class),
            (attempt, receipt) -> receipts.add(receipt),
            api.getBean(OrderOutboxExecutionGate.class), api.getBean(OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK, Clock.class),
            api.getBean(OrderOutboxRelayProperties.class));
        for (long orderId : orderIds) {
            receipts.clear();
            String eventId = "order-confirmed:" + orderId;
            assertThat(relay.runEvent(eventId).value().orElseThrow().outcome()).as("relay " + eventId)
                .isEqualTo(RowOutcome.PUBLISHED);
            assertThat(receipts).hasSize(1);
            OrderOutboxReceipt receipt = receipts.get(0);
            assertThat(receipt.topic()).isEqualTo(topic);
            awaitHandled(eventId, receipt.partition(), receipt.offset(), "APPLIED");
        }
    }

    /** broker 위치의 streamer 처리 결과(ACK 반환 로그)와 group committed offset(위치+1 이상)을 함께 확인한다. */
    private void awaitHandled(String eventId, int partition, long offset, String result) {
        String line = "ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED topic=" + topic + " partition=" + partition
            + " offset=" + offset + " eventId=" + eventId + " result=" + result;
        OrderActivityStreamerChild.await("streamer 처리 " + eventId, limit(OBSERVE), () -> {
            assertThat(streamer.isAlive()).as("streamer 자식 생존").isTrue();
            return streamer.output().contains(line);
        });
        TopicPartition position = new TopicPartition(topic, partition);
        OrderActivityStreamerChild.await("committed offset " + eventId, limit(OBSERVE),
            () -> kafka.committedOffsets(group).getOrDefault(position, -1L) >= offset + 1);
        deliveries.add(eventId + "@" + partition + ":" + offset + "/" + result);
    }

    /** Outbox 에 저장된 같은 key·payload 를 별도 producer 로 실제 topic 에 한 번 더 보낸다. 새 PG 주문은 만들지 않는다. */
    private RecordMetadata republish(long orderId) throws Exception {
        Map<String, Object> outbox = fresh.queryForMap(
            "SELECT message_key, payload FROM order_confirmed_outbox WHERE order_id = ?", orderId);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers(),
            ProducerConfig.CLIENT_ID_CONFIG, "checkout-incident-republish-" + kafka.runId(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            return producer.send(new ProducerRecord<>(topic, (String) outbox.get("message_key"), (String) outbox.get("payload")))
                .get(limit(OBSERVE).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    // ---------------------------------------------------------------- 검증·관찰

    /**
     * 대상 집합과 전체 값표(§2.2)를 새 연결로 확인한다. confirmed·unknown 은 대상 집합, 나머지는 DB 전체 값이다.
     */
    private Map<String, Object> assertDatabase(List<Long> cohort, int confirmed, int unknown, int pgSuccess, int pgTotal,
                                               long stockA, long stockB, long outbox, long activityRows, long quantityA,
                                               long quantityB) {
        Map<String, Long> orderStatus = countBy("SELECT status, COUNT(*) FROM orders WHERE id IN (" + in(cohort)
            + ") GROUP BY status");
        Map<String, Long> paymentStatus = countBy("SELECT status, COUNT(*) FROM payment WHERE order_id IN (" + in(cohort)
            + ") GROUP BY status");
        FixtureState state = pg.state();
        List<String> cohortProviderIds = cohort.stream().map(PaymentModel::providerOrderIdOf).toList();
        long cohortPgSuccess = state.transactions().stream()
            .filter(t -> cohortProviderIds.contains(t.orderId()) && t.status() == PaymentStatus.SUCCESS).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderStatus", orderStatus);
        summary.put("paymentStatus", paymentStatus);
        summary.put("cohortPgSuccess", cohortPgSuccess);
        summary.put("pgTransactions", state.transactions().size());
        summary.put("createReceived", state.counts().createReceived());
        summary.put("stockA", stock(roster.productA()));
        summary.put("stockB", stock(roster.productB()));
        summary.put("outbox", count("SELECT COUNT(*) FROM order_confirmed_outbox"));
        summary.put("activityRows", count("SELECT COUNT(*) FROM product_purchase_activity"));
        summary.put("quantityA", count("SELECT COALESCE(SUM(quantity), 0) FROM product_purchase_activity WHERE product_id = "
            + roster.productA()));
        summary.put("quantityB", count("SELECT COALESCE(SUM(quantity), 0) FROM product_purchase_activity WHERE product_id = "
            + roster.productB()));
        summary.put("reservationRows", count("SELECT COUNT(*) FROM stock_history WHERE cause = 'ORDER_RESERVATION'"));
        summary.put("releaseRows", count("SELECT COUNT(*) FROM stock_history WHERE cause = 'ORDER_RELEASE'"));
        summary.put("deductionRows", count("SELECT COUNT(*) FROM stock_history WHERE cause = 'ORDER_DEDUCTION'"));
        summary.put("unknownPaymentsAll", count("SELECT COUNT(*) FROM payment WHERE status = 'UNKNOWN'"));
        summary.put("confirmedAt", fresh.queryForList("SELECT DISTINCT CAST(confirmed_at AS CHAR) FROM orders WHERE id IN ("
            + in(cohort) + ") AND confirmed_at IS NOT NULL", String.class));

        assertThat(orderStatus.getOrDefault("CONFIRMED", 0L)).as("대상 CONFIRMED").isEqualTo(confirmed);
        assertThat(paymentStatus.getOrDefault("UNKNOWN", 0L)).as("대상 UNKNOWN").isEqualTo(unknown);
        assertThat(cohortPgSuccess).as("대상 PG SUCCESS").isEqualTo(pgSuccess);
        assertThat(summary).containsEntry("pgTransactions", pgTotal).containsEntry("createReceived", (long) pgTotal)
            .containsEntry("stockA", stockA).containsEntry("stockB", stockB).containsEntry("outbox", outbox)
            .containsEntry("activityRows", activityRows).containsEntry("quantityA", quantityA)
            .containsEntry("quantityB", quantityB).containsEntry("releaseRows", 0L).containsEntry("deductionRows", 0L)
            .containsEntry("reservationRows", 2L * pgTotal);
        assertThat(summary).as("DB 전체 UNKNOWN").containsEntry("unknownPaymentsAll", (long) unknown);
        @SuppressWarnings("unchecked")
        List<String> confirmedAt = (List<String>) summary.get("confirmedAt");
        assertThat(confirmedAt).as("최초 확정 시각 고정")
            .containsOnly(CONFIRMED_AT.toString().replace("T", " ").replace("Z", ".000000"));
        return summary;
    }

    /**
     * 합계만으로는 주문 사이의 과다·부족이 상쇄될 수 있어, roster 로 만든 기대 행과 새 DB 조회 행을 정확히 대조한다.
     * 같은 조회 결과를 summary 에 넣어 단계 증거와 assertion 이 같은 값을 보게 한다.
     */
    private void assertOrderRows(Map<String, Object> summary, List<Long> activityOrders, List<Long> reservedOrders) {
        List<String> activity = fresh.query("SELECT order_id, product_id, quantity FROM product_purchase_activity",
            (rs, n) -> rs.getLong(1) + ":" + rs.getLong(2) + ":" + rs.getLong(3));
        // changed_quantity 는 변경량의 크기다(StockChange). 감소 방향은 before - after 로 따로 확인한다.
        List<String> reservations = fresh.query("SELECT order_id, product_id, changed_quantity, "
                + "before_quantity - after_quantity FROM stock_history WHERE cause = 'ORDER_RESERVATION'",
            (rs, n) -> rs.getLong(1) + ":" + rs.getLong(2) + ":" + rs.getLong(3) + ":" + rs.getLong(4));
        summary.put("activityRowsByOrder", sorted(activity));
        summary.put("reservationRowsByOrder", sorted(reservations));
        assertExactRows("구매 활동", activity, expectedRows(activityOrders, roster.productA(), roster.productB(), 2, 1));
        assertExactRows("재고 예약", reservations, expectedRows(reservedOrders, roster.productA(), roster.productB(), "2:2", "1:1"));
    }

    /** 주문마다 상품 A·B 한 행씩의 "orderId:productId:quantity" 기대값. */
    static List<String> expectedRows(List<Long> orders, long productA, long productB, long quantityA, long quantityB) {
        return expectedRows(orders, productA, productB, String.valueOf(quantityA), String.valueOf(quantityB));
    }

    /** 상품별 값이 여러 열이면 "changed:decrease"처럼 이어 붙인 값을 준다. */
    static List<String> expectedRows(List<Long> orders, long productA, long productB, String valueA, String valueB) {
        List<String> rows = new ArrayList<>();
        for (long orderId : orders) {
            rows.add(orderId + ":" + productA + ":" + valueA);
            rows.add(orderId + ":" + productB + ":" + valueB);
        }
        return sorted(rows);
    }

    /** 중복·누락·예상 밖 행을 모두 드러내도록 정렬한 목록 전체를 비교한다. */
    static void assertExactRows(String label, List<String> actual, List<String> expected) {
        assertThat(sorted(actual)).as(label + " 주문별 행").containsExactlyElementsOf(sorted(expected));
    }

    private static List<String> sorted(List<String> rows) {
        return rows.stream().sorted().toList();
    }

    private static List<Long> concat(List<Long> first, List<Long> second) {
        List<Long> joined = new ArrayList<>(first);
        joined.addAll(second);
        return joined;
    }

    /** 실제 management port 의 health·Prometheus 를 직접 GET 하고 gauge 가 기대값인지 본다. 없는 표본을 0 으로 채우지 않는다. */
    private String assertDirectObservation(int expectedUnknown) {
        ResponseEntity<String> health = get("/actuator/health");
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
        ResponseEntity<String> prometheus = get("/actuator/prometheus");
        assertThat(prometheus.getStatusCode().value()).isEqualTo(200);
        List<String> samples = prometheus.getBody().lines().filter(line -> line.startsWith("commerce_payment_unknown{")).toList();
        assertThat(samples).as("UNKNOWN gauge 표본 하나").hasSize(1);
        String raw = samples.get(0).substring(samples.get(0).lastIndexOf(' ') + 1);
        assertThat(IncidentEvidenceWriter.GaugeValue.of(raw)).as("UNKNOWN gauge")
            .isEqualTo(new IncidentEvidenceWriter.GaugeValue(raw, true, (double) expectedUnknown));
        return "health " + Instant.now() + "\n" + health.getBody() + "\n\n" + prometheus.getBody();
    }

    private void writeStageEvidence(IncidentStage target, Map<String, Object> summary, String directObservation) {
        FixtureState state = pg.state();
        Map<String, Object> pgState = new LinkedHashMap<>();
        pgState.put("transactions", state.transactions().stream().map(t -> Map.of("transactionKey", t.transactionKey(),
            "orderId", t.orderId(), "amount", t.amount(), "status", t.status().name())).toList());
        pgState.put("counts", Map.of("createReceived", state.counts().createReceived(),
            "lookupByKeyReceived", state.counts().lookupByKeyReceived(),
            "lookupByOrderReceived", state.counts().lookupByOrderReceived(), "callbackSent", state.counts().callbackSent()));
        pgState.put("journal", state.journal().stream().map(CheckoutIncidentScenario::journal).toList());
        pgState.put("activeRequests", state.activeRequests());
        pgState.put("activeCallbackJobs", state.activeCallbackJobs());
        String logs = diagnosticLines().stream().collect(Collectors.joining("\n", "", "\n"));
        writer.writeStage(target, StageSnapshot.of(
            new StageSnapshot.Json("summary", summary),
            new StageSnapshot.Json("orders", orders(target == IncidentStage.BASELINE ? roster.baseline() : roster.fault())),
            new StageSnapshot.Json("preserved", preserved()),
            new StageSnapshot.Json("pg-state", pgState),
            new StageSnapshot.Json("plan-ledger", List.copyOf(planLedger)),
            new StageSnapshot.Json("deliveries", List.copyOf(deliveries)),
            new StageSnapshot.Raw("direct-observation.txt", directObservation.getBytes(StandardCharsets.UTF_8)),
            new StageSnapshot.Raw("diagnostic-log.txt", logs.getBytes(StandardCharsets.UTF_8))));
    }

    private static Map<String, Object> journal(JournalEntry entry) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sequence", entry.sequence());
        fields.put("operation", entry.operation().name());
        fields.put("target", entry.target());
        fields.put("receivedAt", String.valueOf(entry.receivedAt()));
        fields.put("completedAt", String.valueOf(entry.completedAt()));
        fields.put("responseStatus", entry.responseStatus());
        fields.put("createdTransactionKey", entry.createdTransactionKey());
        fields.put("failure", entry.failure() == null ? null : entry.failure().name());
        return fields;
    }

    /** 실제 주문/결제 대응표. 카드 값은 없다. */
    private List<Map<String, Object>> orders(List<Long> ids) {
        return fresh.query("SELECT o.id, o.status, o.total_amount, o.paid_amount, CAST(o.confirmed_at AS CHAR), p.attempt_key, "
                + "p.provider_order_id, p.transaction_key, p.status, p.amount, p.verification_status FROM orders o "
                + "JOIN payment p ON p.order_id = o.id WHERE o.id IN (" + in(ids) + ") ORDER BY o.id",
            (rs, n) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderId", rs.getLong(1));
                row.put("orderStatus", rs.getString(2));
                row.put("totalAmount", rs.getLong(3));
                row.put("paidAmount", rs.getObject(4));
                row.put("confirmedAt", rs.getString(5));
                row.put("attemptKey", rs.getString(6));
                row.put("providerOrderId", rs.getString(7));
                row.put("transactionKey", rs.getString(8));
                row.put("paymentStatus", rs.getString(9));
                row.put("amount", rs.getLong(10));
                row.put("verificationStatus", rs.getString(11));
                return row;
            });
    }

    /** 포인트 잔액·이력과 쿠폰·캠페인의 저장 값 전체. 단순 행 수가 아니라 값을 비교한다. */
    private Map<String, Object> preserved() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("point", rows("SELECT * FROM point WHERE user_id = " + roster.userId()));
        values.put("pointHistory", rows("SELECT h.* FROM point_history h JOIN point p ON p.id = h.point_id WHERE p.user_id = "
            + roster.userId() + " ORDER BY h.id"));
        values.put("coupon", rows("SELECT * FROM coupon WHERE user_id = " + roster.userId() + " ORDER BY id"));
        values.put("couponCampaign", rows("SELECT c.* FROM coupon_campaign c JOIN coupon k ON k.campaign_id = c.id "
            + "WHERE k.user_id = " + roster.userId() + " ORDER BY c.id"));
        return values;
    }

    private List<Map<String, String>> rows(String sql) {
        return fresh.queryForList(sql).stream().map(row -> {
            Map<String, String> text = new LinkedHashMap<>();
            row.forEach((key, value) -> text.put(key, String.valueOf(value)));
            return text;
        }).toList();
    }

    // ---------------------------------------------------------------- PG·plan

    /** 시나리오의 유일한 plan 등록 경로. 성공 여부를 원장에 남기고 실패는 다시 시도하지 않는다. */
    private void registerPlan(PgOperation operation, String target, String planName, ResponsePlan plan) {
        try {
            pg.enqueuePlan(operation, target, plan);
            planLedger.add(new PlanRegistration(planLedger.size() + 1, stage, operation.name(), target, planName, true));
        } catch (RuntimeException e) {
            planLedger.add(new PlanRegistration(planLedger.size() + 1, stage, operation.name(), target, planName, false));
            throw e;
        }
    }

    private long successfulRegistrations() {
        return planLedger.stream().filter(PlanRegistration::succeeded).count();
    }

    private TransactionSnapshot singleTransaction(String providerOrderId) {
        List<TransactionSnapshot> found = pg.state().transactions().stream()
            .filter(t -> t.orderId().equals(providerOrderId)).toList();
        assertThat(found).as("PG 거래 " + providerOrderId).hasSize(1);
        return found.get(0);
    }

    private void awaitPgIdle() {
        OrderActivityStreamerChild.await("PG 요청·콜백 작업 idle", limit(OBSERVE), () -> {
            FixtureState state = pg.state();
            return state.activeRequests() == 0 && state.activeCallbackJobs() == 0
                && state.journal().stream().allMatch(entry -> entry.completedAt() != null);
        });
    }

    // ---------------------------------------------------------------- 진단 로그 capture

    /** PG 경계·TX 진단 logger 에 자기 appender 하나를 붙인다. 두 logger 의 사건이 기록 순서대로 한 목록에 쌓인다. */
    private void attachDiagnostics() {
        diagnostics.start();
        for (Class<?> source : List.of(PgHttpGateway.class, PaymentTxDiagnostics.class)) {
            ((Logger) LoggerFactory.getLogger(source)).addAppender(diagnostics);
        }
    }

    private void detachDiagnostics() {
        for (Class<?> source : List.of(PgHttpGateway.class, PaymentTxDiagnostics.class)) {
            ((Logger) LoggerFactory.getLogger(source)).detachAppender(diagnostics);
        }
        diagnostics.stop();
    }

    private List<String> diagnosticLines() {
        synchronized (diagnostics) {
            return diagnostics.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    // ---------------------------------------------------------------- 자원

    /** 기동 직후 필드에 넣어, 이후 준비 assertion·증거 기록이 실패해도 정리 경로가 context 를 닫게 한다. */
    private void bootApi() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        String callback = "http://127.0.0.1:" + port + "/api/v1/payments/callback";
        // 기본은 임의 port. 공유 Prometheus 로 보고 싶을 때만 opt-in 으로 고정 port(8081)를 준다.
        String observe = System.getProperty("w10.observe-management-port", "");
        int requestedManagement = observe.isBlank() ? 0 : Integer.parseInt(observe);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test")
            .initializers(ctx -> ((BeanDefinitionRegistry) ctx.getBeanFactory()).registerBeanDefinition(
                "checkoutIncidentClockReplacer", new RootBeanDefinition(ClockReplacer.class, () -> new ClockReplacer(confirmationClock))))
            .run("--server.port=" + port, "--server.address=127.0.0.1",
                "--management.server.port=" + requestedManagement, "--management.server.address=127.0.0.1",
                "--management.endpoint.health.show-details=never",
                "--spring.jpa.hibernate.ddl-auto=create",
                "--commerce.pg.base-url=" + pg.baseUrl(),
                "--commerce.pg.callback-url=" + callback,
                "--spring.kafka.bootstrap-servers=" + kafka.bootstrapServers(),
                "--spring.kafka.admin.properties.bootstrap.servers=" + kafka.bootstrapServers(),
                "--commerce.order-events.topic=" + topic,
                "--commerce.order-events.relay.enabled=false",
                "--commerce.order-events.producer-properties[client.id]=checkout-incident-" + kafka.runId());
        api = context;
        int actualPort = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        managementPort = Integer.parseInt(context.getEnvironment().getProperty("local.management.port", "-1"));
        assertThat(actualPort).as("실제 API port 와 callback port 일치").isEqualTo(port);
        if (requestedManagement == 0) {
            assertThat(managementPort).as("실제 임의 management port").isPositive().isNotEqualTo(8081);
        } else {
            assertThat(managementPort).as("요청한 관찰 management port").isEqualTo(requestedManagement);
        }
        assertThat(context.getBean(OrderConfirmationClockConfig.ORDER_CONFIRMATION_CLOCK, Clock.class)).isSameAs(confirmationClock);
        assertThat(context.getBean(OrderOutboxRelayProperties.class).enabled()).as("자동 relay OFF").isFalse();
        writer.recordResource("api", Map.of("port", actualPort, "managementPort", managementPort,
            "managementPortRequested", requestedManagement, "callbackUrl", callback, "relayEnabled", false));
    }

    private void startStreamer() throws Exception {
        List<String> tables = fresh.queryForList(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()", String.class);
        OrderOutboxE2eLifecycleSupport.launchChild(ownership, tables, new ChildLaunch() {
            @Override
            public void start() throws Exception {
                streamer = OrderActivityStreamerChild.start(kafka, topic, group, LABEL);
            }

            @Override
            public void awaitReady() {
                streamer.awaitReady(limit(CHILD));
            }
        });
        streamer.awaitAssignment(limit(CHILD));
        writer.recordResource("streamer", Map.of("topic", topic, "group", group, "logDir", "build/" + LABEL.toLowerCase()));
    }

    /**
     * §9.2 순서: 진입 중단 → 자식 종료 → 데이터(자식 종료 확인 시만) → API·PG → 구매 테이블 → Kafka. 실패는 기록하고 던진다.
     * helper 가 예외로 끝나면 남긴 자원 목록을 받지 못하므로 kept 를 빈 목록이 아닌 UNKNOWN 으로 남긴다.
     */
    private void release() {
        List<String> kept = null;
        RuntimeException failure = null;
        try {
            kept = OrderOutboxE2eLifecycleSupport.release(ownership, new CleanupSteps() {
                @Override
                public void stopRelay() throws InterruptedException {
                    if (api != null && api.isActive()) {
                        OrderOutboxExecutionGate gate = api.getBean(OrderOutboxExecutionGate.class);
                        gate.stop();
                        if (!gate.awaitIdle(Duration.ofSeconds(10))) {
                            throw new IllegalStateException("진행 중인 relay 가 10초 안에 끝나지 않았습니다.");
                        }
                    }
                }

                @Override
                public void restoreTopicConfig() {
                }

                @Override
                public void stopChild() throws InterruptedException {
                    try {
                        WeeklyRankingBatchChild.stopRemaining(CHILD);
                    } finally {
                        if (streamer != null) {
                            streamer.stop(LABEL, CHILD);
                        }
                    }
                }

                @Override
                public boolean childExited() {
                    return (streamer == null || !streamer.isAlive()) && WeeklyRankingBatchChild.allExited();
                }

                @Override
                public void truncateApiData() {
                    if (api != null && api.isActive()) {
                        api.getBean(DatabaseCleanUp.class).truncateAllTables();
                        api.getBean(RedisCleanUp.class).truncateAll();
                    }
                }

                @Override
                public void closeApi() {
                    detachDiagnostics();
                    if (api != null) {
                        api.close();
                    }
                }

                @Override
                public void closePg() {
                    if (pg != null) {
                        pg.close();
                    }
                }

                @Override
                public void dropPurchaseTables() {
                    List.of("processed_order_confirmation", "product_purchase_activity")
                        .forEach(table -> fresh.execute("DROP TABLE IF EXISTS " + table));
                }

                @Override
                public void closeTopicAdmin() {
                }

                @Override
                public void closeKafka() {
                    if (kafka != null) {
                        kafka.close();
                    }
                }
            });
        } catch (RuntimeException e) {
            failure = e;
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("cleanup", "FAILED");
            context.put("message", e.getMessage());
            writer.recordFailureKeeping(e, stage, context);
        }
        Map<String, Object> cleanup = new LinkedHashMap<>();
        cleanup.put("releaseCompleted", failure == null);
        cleanup.put("kept", kept == null ? "UNKNOWN" : kept);
        cleanup.put("streamerExited", streamer == null || !streamer.isAlive());
        cleanup.put("batchChildrenExited", WeeklyRankingBatchChild.allExited());
        cleanup.put("pgTerminated", pg == null || pg.isTerminated());
        try {
            writer.recordResource("cleanup", cleanup);
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // ---------------------------------------------------------------- HTTP·SQL 도구

    private ResponseEntity<JsonNode> post(Long user, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", user.toString());
        return http.exchange(apiUrl() + path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<String> asUser(Long user, HttpMethod method, String path) {
        return http.exchange(apiUrl() + path, method, new HttpEntity<>(userHeaders(user)), String.class);
    }

    private static HttpHeaders userHeaders(Long user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", user.toString());
        return headers;
    }

    private ResponseEntity<String> get(String managementPath) {
        return observer.exchange(URI.create("http://127.0.0.1:" + managementPort + managementPath), HttpMethod.GET, null,
            String.class);
    }

    private String apiUrl() {
        return "http://127.0.0.1:" + ((ServletWebServerApplicationContext) api).getWebServer().getPort();
    }

    private Map<String, Long> countBy(String sql) {
        Map<String, Long> counts = new LinkedHashMap<>();
        fresh.query(sql, rs -> {
            counts.put(rs.getString(1), rs.getLong(2));
        });
        return counts;
    }

    private long count(String sql) {
        Long value = fresh.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long stock(long productId) {
        return count("SELECT stock_quantity FROM product WHERE id = " + productId);
    }

    private long weeklyRankingInstances() {
        List<String> tables = fresh.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
            + "AND TABLE_NAME = 'BATCH_JOB_INSTANCE'", String.class);
        return tables.isEmpty() ? 0 : count("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE WHERE JOB_NAME = 'weeklyRanking'");
    }

    private static String in(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private Duration limit(Duration stageLimit) {
        Duration remaining = deadline.remaining();
        assertThat(remaining.isZero()).as("시나리오 전체 기한 초과").isFalse();
        return remaining.compareTo(stageLimit) < 0 ? remaining : stageLimit;
    }

    /** 비정상 상태도 응답으로 받아 assertion 이 원문을 보게 한다. connect 는 3초, read 는 호출 종류별 값이다. */
    static RestTemplate client(Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(HTTP_TIMEOUT);
        factory.setReadTimeout(readTimeout);
        RestTemplate template = new RestTemplate(factory);
        template.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return template;
    }

    /** 주문 확정 Clock 만 고정 Clock 으로 바꾼다. Outbox·감사·Kafka 시각은 실제 시간이다. */
    private static final class ClockReplacer implements BeanDefinitionRegistryPostProcessor {

        private final SettableClock clock;

        ClockReplacer(SettableClock clock) {
            this.clock = clock;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            String name = OrderConfirmationClockConfig.ORDER_CONFIRMATION_CLOCK;
            registry.removeBeanDefinition(name);
            registry.registerBeanDefinition(name, new RootBeanDefinition(Clock.class, () -> clock));
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        }
    }
}
