package com.loopers.domains.order.in;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.E2eApiTestApplication;
import com.loopers.E2eBatchContext;
import com.loopers.E2eStreamerContext;
import com.loopers.E2eStreamerContext.ConsumeObservation;
import com.loopers.E2eStreamerContext.ConsumeObservation.Consumed;
import com.loopers.application.purchase.ConsumeResult;
import com.loopers.batch.job.ranking.WeeklyRankingLauncher;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.order.app.OrderOutboxRelay;
import com.loopers.domains.payment.app.port.PaymentTransaction;
import com.loopers.domains.payment.app.port.PaymentTransactionStatus;
import com.loopers.domains.payment.app.port.PaymentTransactionsLookup;
import com.loopers.domains.payment.out.TestPgPaymentGateway;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.payment.out.TestPgServer.StoredTransaction;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.interfaces.consumer.OrderEventsListener;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.loopers.domains.order.in.PrometheusScrape.outcome;
import static com.loopers.domains.order.in.PrometheusScrape.scrape;
import static com.loopers.domains.order.in.PrometheusScrape.unresolved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// 10주차 2단계 사고 연결 테스트. 같은 DB·같은 TestPgServer 거래를 이어 한 사고를 재현한다:
// 정상 기준선 → 응답 유실 3건 → 증거 보존 → 가설 판정 → 본인 reconcile 복구 → 반복·보호 → 구매 활동·주간 랭킹.
// 단계 사이에 DB·PG 거래를 초기화하지 않고, 경계마다 DB·PG·actuator를 새로 조회해 같은 주문 집합으로 불변 조건을 맞춘다.
// 고객 API·callback·랭킹은 RANDOM_PORT 실제 HTTP, health·prometheus는 management 포트 원문이다. 대역은 PG(TestPgServer, 실제 HTTP)와
// 테스트 clock뿐이고, streamer·batch는 같은 JVM의 별도 컨텍스트다. 테스트 프로필은 재조회·만료·relay·gauge 스케줄러가 꺼져 있어
// 유실 3건의 자동 조회·callback은 테스트가 부르기 전까지 일어나지 않는다. gauge는 refresh()를, relay는 runOnce()를 직접 부른다.
@SpringBootTest(classes = E2eApiTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import(PaymentLookupScheduleIntegrationTest.TimeConfig.class)
class CheckoutIncidentE2ETest {
    private static final String TOPIC = "order-events-incident-e2e-" + UUID.randomUUID();
    private static final String REQUEST_TOPIC = "order-event-republish-requests-incident-e2e-" + UUID.randomUUID();
    private static final String REQUEST_GROUP = "commerce-api-republish-incident-e2e-" + UUID.randomUUID();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ASSIGNMENT_DEADLINE = Duration.ofSeconds(30);
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(60);
    // 2026-10-05(월) 00:00 UTC ~ 2026-10-12(월) 00:00 UTC(제외). 다른 e2e가 쓰지 않는 주이고 batch 고정 시각(11-02) 기준으로 끝난 주다.
    private static final LocalDate WEEK = LocalDate.parse("2026-10-05");
    private static final Instant BASELINE_AT = Instant.parse("2026-10-06T01:00:00Z");
    private static final Instant FAULT_AT = Instant.parse("2026-10-07T01:00:00Z");
    // 복구·반복은 결제 대기 상한(2분) 안에서 한다. 운영이라면 만료 스케줄러가 먼저 종결했을 시각으로 가지 않는다.
    private static final Instant RECOVERY_AT = FAULT_AT.plusSeconds(60);
    private static final Instant REPEAT_AT = FAULT_AT.plusSeconds(90);
    private static final Instant FOLLOWUP_AT = FAULT_AT.plusSeconds(300);
    private static final long NORMAL_USER = 1;
    private static final long FAULT_USER = 2;
    private static final long OTHER_USER = 3;
    private static final int ORDERS_PER_SET = 10;
    private static final int LOST = 3;
    private static final int STOCK = 30;
    private static final long PRICE = 1000;
    private static final List<String> UNRESOLVED_STATUSES = List.of("send_started", "pending", "unknown", "needs_review");
    private static final String SERVICE_LOGGER = OrderApplicationService.class.getName();
    private static final String ADAPTER_LOGGER = TestPgPaymentGateway.class.getName();
    private static final String CREATE_RESULT = "PG 결제 생성 시도 결과";
    private static final String LOOKUP_JUDGMENT = "PG 조회 판정";
    private static final String CARD_DIGITS = TestPgFixture.CARD_NO.replace("-", "");
    private static final int FAILURE_MESSAGE_LIMIT = 500;

    @LocalServerPort private int port;
    @LocalManagementPort private int managementPort;
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrapServers;
    @Autowired private BrandApplicationService brandApplicationService;
    @Autowired private ProductApplicationService productApplicationService;
    @Autowired private UserJpaRepository userJpaRepository;
    @Autowired private TestPgFixture testPgFixture;
    @Autowired private MutablePaymentClock clock;
    @Autowired private UnresolvedPaymentMetrics unresolvedPaymentMetrics;
    @Autowired private OrderOutboxRelay orderOutboxRelay;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @Autowired private Environment environment;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<TrackedOrder> tracked = new ArrayList<TrackedOrder>();
    private final List<String> armedLosses = new ArrayList<String>();
    private final SnapshotAppender logs = new SnapshotAppender();
    private TestPgServer server;
    private IncidentEvidence evidence;
    private ConfigurableApplicationContext streamer;
    private ConfigurableApplicationContext batch;
    private boolean pgStarted;
    private boolean rankingTablesCreated;
    private Level serviceLevel;
    private Level adapterLevel;
    private long productId;
    private int logCursor;
    private String currentStage = "START";
    private double confirmedAtStart;
    private Map<Long, JsonNode> pointsAtStart;
    private Map<Long, JsonNode> couponsAtStart;
    private Map<Long, String> confirmedAtAfterRecovery;

    private record TrackedOrder(String set, long orderId, long userId, String attemptKey, String providerOrderId) {
        boolean lost() {
            return set.equals("fault-lost");
        }
    }

    private record HttpResult(int status, String body) { }

    @DynamicPropertySource
    static void incidentTopics(DynamicPropertyRegistry registry) {
        registry.add("commerce.kafka.topics.order-events", () -> TOPIC);
        registry.add("commerce.kafka.topics.order-events-partitions", () -> 1);
        registry.add("commerce.kafka.topics.order-event-republish-requests", () -> REQUEST_TOPIC);
        registry.add("commerce.kafka.topics.order-event-republish-requests-partitions", () -> 1);
        registry.add("commerce.kafka.groups.order-event-republish", () -> REQUEST_GROUP);
    }

    // 증거를 다 쓴 뒤에 돈다. 같은 MySQL을 쓰는 다른 e2e를 위해 DB와 이 테스트의 랭킹 행을 지우고 PG 서버를 닫는다. 증거 파일은 지우지 않는다.
    @AfterEach
    void cleanAfterEvidence() throws InterruptedException {
        try {
            if (rankingTablesCreated) {
                deleteOwnRankingRows();
            }
            jdbc.update("delete from order_items");
            databaseCleanUp.truncateAllTables();
        } finally {
            try {
                if (pgStarted) {
                    testPgFixture.close();
                }
            } finally {
                try {
                    ConsumeObservation.CONSUMED.clear();
                } finally {
                    httpClient.close();
                }
            }
        }
    }

    @Test
    @DisplayName("응답 유실 3건 사고는 health UP인데 unknown gauge 3으로 보이고, 경계 기록으로 응답 유실만 채택되며, "
        + "본인 reconcile이 create 추가 없이 같은 주문을 한 번씩 확정해 구매 활동 20건·주간 랭킹 점수 20으로 이어진다")
    void checkoutIncidentLifecycle() throws Exception {
        attachLogCapture();
        Throwable primary = null;
        try {
            currentStage = "PREPARE";
            prepare();
            currentStage = "BASELINE";
            baseline();
            currentStage = "FAULT";
            fault();
            currentStage = "PRESERVE";
            String faultSha256 = preserveFaultEvidence();
            currentStage = "DIAGNOSIS";
            diagnose(faultSha256);
            currentStage = "RECOVERY";
            recover(faultSha256);
            currentStage = "REPEAT";
            repeatAndProtect();
            currentStage = "FOLLOWUP";
            followUp();

            currentStage = "EVIDENCE_CHECK";
            assertThat(evidence.sha256("fault")).as("복구·후속 뒤에도 fault.json이 S3 때와 같다").isEqualTo(faultSha256);
            for (Path file : evidence.files()) {
                assertNoCardNumber(Files.readString(file), file.getFileName().toString());
            }
        } catch (Throwable failure) {
            primary = failure;
            recordFailure(failure);
            throw failure;
        } finally {
            releaseOwnInjections(primary);
        }
    }

    // 단계 진행 중 실패하면 그 시점 상태를 단계 파일과 같은 구성으로 failure.json에 남긴다.
    // 증거 쓰기가 실패해도 원래 예외를 가리지 않도록 suppressed로만 붙인다.
    private void recordFailure(Throwable failure) {
        if (evidence == null) {
            return;
        }
        try {
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("failedStage", currentStage);
            summary.put("exceptionClass", failure.getClass().getName());
            summary.put("message", failureMessage(failure));
            evidence.write("failure", stage("FAILURE", scrape(managementPort), Map.of("failure", summary)));
        } catch (Throwable writeFailure) {
            failure.addSuppressed(writeFailure);
        }
    }

    // 단언 메시지(설명·기대값·실제값)를 한 줄로 합쳐 앞부분만 남기고, 혹시 섞인 카드번호는 가린다.
    private static String failureMessage(Throwable failure) {
        String message = String.valueOf(failure.getMessage()).strip()
            .replace(TestPgFixture.CARD_NO, "[card]").replace(CARD_DIGITS, "[card]");
        String oneLine = String.join(" / ", message.lines().map(String::strip).filter(line -> !line.isEmpty()).toList());
        if (oneLine.length() > FAILURE_MESSAGE_LIMIT) {
            return oneLine.substring(0, FAILURE_MESSAGE_LIMIT) + "…";
        }
        return oneLine;
    }

    // 준비: 사용자 1(정상 10)·2(장애 10)·3(타인), 공개 상품 1개 재고 30. 포인트·쿠폰·outcome counter 시작값을 기록한다.
    private void prepare() throws Exception {
        ConsumeObservation.CONSUMED.clear();
        evidence = IncidentEvidence.open();
        server = testPgFixture.start();
        pgStarted = true;
        userJpaRepository.save(new UserJpaEntity(NORMAL_USER));
        userJpaRepository.save(new UserJpaEntity(FAULT_USER));
        userJpaRepository.save(new UserJpaEntity(OTHER_USER));
        long brandId = brandApplicationService.create("사고 브랜드").id();
        productId = productApplicationService.create(brandId, "사고 상품", PRICE, STOCK).id();
        assertThat(availableStock()).isEqualTo(STOCK);
        pointsAtStart = Map.of(NORMAL_USER, points(NORMAL_USER), FAULT_USER, points(FAULT_USER));
        couponsAtStart = Map.of(NORMAL_USER, coupons(NORMAL_USER), FAULT_USER, coupons(FAULT_USER));
        confirmedAtStart = confirmedCounter(scrape(managementPort));

        String url = environment.getRequiredProperty("datasource.mysql-jpa.main.jdbc-url");
        String username = environment.getRequiredProperty("datasource.mysql-jpa.main.username");
        String password = environment.getRequiredProperty("datasource.mysql-jpa.main.password");
        streamer = E2eStreamerContext.start(TOPIC, bootstrapServers, url, username, password);
        batch = E2eBatchContext.start(url, username, password);
        rankingTablesCreated = true;
    }

    // S1 BASELINE: 유실 지정 없음 → 사용자 1이 10건 confirm(ACCEPT_PENDING) → PG에서 SUCCESS로 바꿈 → 주문마다 callback HTTP로 확정.
    private void baseline() throws Exception {
        clock.set(BASELINE_AT);
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        assertThat(server.lostResponseOrderIds()).as("기준선에는 응답 유실 지정이 없다").isEmpty();
        for (int index = 0; index < ORDERS_PER_SET; index++) {
            TrackedOrder order = placeOrder("normal", NORMAL_USER);
            HttpResult confirmed = confirm(order);
            assertThat(confirmed.status()).isEqualTo(202);
            assertThat(json(confirmed).at("/data/status").asText()).isEqualTo("PAYMENT_PENDING");
            assertProviderOrderIdAsPredicted(order);
        }
        for (TrackedOrder order : set("normal")) {
            server.changeStatus(storedTransactionKey(order.orderId()), "SUCCESS");
            assertThat(callback(order).status()).isEqualTo(200);
        }

        List<TrackedOrder> normal = set("normal");
        assertThat(normal).allSatisfy(order -> assertThat(orderRow(order.orderId()))
            .containsEntry("status", "CONFIRMED").containsEntry("provider_status", "SUCCESS"));
        assertThat(normal).noneMatch(order -> "UNKNOWN".equals(orderRow(order.orderId()).get("provider_status")));
        assertThat(normal).allSatisfy(order -> assertThat(confirmedInstant(order.orderId()))
            .as("confirmedAt order=%d", order.orderId()).isEqualTo(BASELINE_AT));
        assertPgSuccessOncePerOrder(normal);
        assertThat(server.transactions()).as("누적 PG 거래").hasSize(ORDERS_PER_SET);
        assertThat(server.createCalls()).as("누적 create").isEqualTo(ORDERS_PER_SET);
        assertThat(availableStock()).isEqualTo(STOCK - ORDERS_PER_SET);
        assertHealthUp();
        String prometheus = refreshedPrometheus();
        assertUnresolved(prometheus, Map.of("send_started", 0.0, "pending", 0.0, "unknown", 0.0, "needs_review", 0.0));
        assertThat(confirmedCounter(prometheus) - confirmedAtStart).as("outcome confirmed 증가").isEqualTo(ORDERS_PER_SET);

        evidence.write("baseline", stage("BASELINE", prometheus, Map.of()));
    }

    // S2 FAULT: 사용자 2의 10건 중 앞 3건만 응답 유실. providerOrderId는 confirm 안에서 정해지고 create가 바로 나가므로
    // 주문 생성 → 규칙(LP-ORD-%010d)으로 후보 계산·유실 지정 → confirm 순서이고, confirm 직후 DB 실제 값과 같은지 확인한다(P2).
    private void fault() throws Exception {
        clock.set(FAULT_AT);
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        long lookupsBefore = server.lookupCalls();
        for (int index = 0; index < ORDERS_PER_SET; index++) {
            boolean lose = index < LOST;
            TrackedOrder order = placeOrder(lose ? "fault-lost" : "fault-confirmed", FAULT_USER);
            if (lose) {
                armedLosses.add(order.providerOrderId());
                testPgFixture.loseResponseAfterSuccess(order.providerOrderId());
            }
            HttpResult confirmed = confirm(order);
            assertProviderOrderIdAsPredicted(order);
            if (lose) {
                assertThat(confirmed.status()).isEqualTo(202);
                assertThat(json(confirmed).at("/data/status").asText()).isEqualTo("PAYMENT_PENDING");
            } else {
                assertThat(confirmed.status()).isEqualTo(200);
                assertThat(json(confirmed).at("/data/status").asText()).isEqualTo("CONFIRMED");
            }
        }

        List<TrackedOrder> faultSet = faultSet();
        assertThat(faultSet).filteredOn(order -> "CONFIRMED".equals(orderRow(order.orderId()).get("status"))).hasSize(7);
        assertThat(set("fault-lost")).hasSize(LOST).allSatisfy(order -> assertThat(orderRow(order.orderId()))
            .containsEntry("status", "PAYMENT_PENDING").containsEntry("provider_status", "UNKNOWN")
            .containsEntry("transaction_key", null));
        assertThat(set("fault-confirmed")).allSatisfy(order -> assertThat(confirmedInstant(order.orderId()))
            .as("confirmedAt order=%d", order.orderId()).isEqualTo(FAULT_AT));
        assertThat(set("fault-lost")).allSatisfy(order -> assertThat(confirmedAt(order.orderId()))
            .as("미확정 confirmedAt order=%d", order.orderId()).isNull());
        assertThat(server.lookupCalls()).as("FAULT 구간에는 callback·자동 조회가 보류되어 PG 조회가 없다").isEqualTo(lookupsBefore);
        assertPgSuccessOncePerOrder(faultSet);
        assertThat(server.transactions()).as("누적 PG 거래").hasSize(2 * ORDERS_PER_SET);
        assertThat(server.createCalls()).as("누적 create").isEqualTo(2 * ORDERS_PER_SET);
        assertThat(availableStock()).as("유실 3건도 예약이 유지된다").isEqualTo(STOCK - 2 * ORDERS_PER_SET);
        assertHealthUp();
        String prometheus = refreshedPrometheus();
        assertUnresolved(prometheus, Map.of("send_started", 0.0, "pending", 0.0, "unknown", (double) LOST, "needs_review", 0.0));
        assertThat(confirmedCounter(prometheus) - confirmedAtStart).isEqualTo(ORDERS_PER_SET + ORDERS_PER_SET - LOST);

        evidence.write("fault", stage("FAULT", prometheus, Map.of()));
    }

    // S3 증거 보존: 복구 전 fault.json의 hash를 기록하고, 파일을 다시 읽어 3건의 ID·상태·create 수·PG 상태를 단언한다.
    private String preserveFaultEvidence() throws Exception {
        String sha256 = evidence.sha256("fault");
        JsonNode fault = evidence.read("fault");
        assertNoCardNumber(evidence.text("fault"), "fault.json");
        assertThat(fault.path("stage").asText()).isEqualTo("FAULT");
        assertThat(fault.path("prometheusRaw").asText()).contains(PrometheusScrape.UNRESOLVED_METRIC);
        assertThat(fault.path("healthRaw").asText()).contains("\"UP\"");
        for (TrackedOrder order : set("fault-lost")) {
            JsonNode saved = savedOrder(fault, order.orderId());
            assertThat(saved.path("set").asText()).isEqualTo("fault-lost");
            assertThat(saved.path("attemptKey").asText()).isEqualTo(order.attemptKey());
            assertThat(saved.path("providerOrderId").asText()).isEqualTo(order.providerOrderId());
            assertThat(saved.path("status").asText()).isEqualTo("PAYMENT_PENDING");
            assertThat(saved.path("providerStatus").asText()).isEqualTo("UNKNOWN");
            assertThat(saved.path("createCalls").asInt()).isEqualTo(1);
            assertThat(saved.path("pgTransactions")).hasSize(1);
            assertThat(saved.path("pgTransactions").get(0).path("status").asText()).isEqualTo("SUCCESS");
        }
        return sha256;
    }

    // S4 가설 판정: 읽기 전용 PG 조회(새 클라이언트로 verifyOrder 직접 호출)와 경계 로그만 쓴다. commerce 상태는 바뀌지 않아야 한다.
    // H1 응답 유실 = create Unknown WARN(HttpTimeoutException) 1 + 반영 ERROR 0 + PG SUCCESS·금액 일치.
    // H2 내부 반영 실패 = 결과를 받았거나(Accepted) 조회 Verified INFO가 있고 반영 ERROR가 있어야 한다.
    // H3 PG 실패·미발송 = PG 거래가 없거나 FAILED이거나 NotSent WARN이 있어야 한다.
    private void diagnose(String faultSha256) throws Exception {
        Map<Long, Map<String, Object>> before = ledger();
        long createsBefore = server.createCalls();
        List<Map<String, Object>> judgments = new ArrayList<Map<String, Object>>();
        // 요약은 주문별 판정에서 모은다: 채택·기각 → 가설 ID → 그 판정을 받은 주문 ID.
        Map<String, Map<String, List<Long>>> verdict = new LinkedHashMap<String, Map<String, List<Long>>>();
        verdict.put("adopted", new LinkedHashMap<String, List<Long>>());
        verdict.put("rejected", new LinkedHashMap<String, List<Long>>());
        for (TrackedOrder order : set("fault-lost")) {
            PaymentTransactionsLookup lookup = testPgFixture.newClient().verifyOrder(order.providerOrderId(), null);
            assertThat(lookup).isInstanceOf(PaymentTransactionsLookup.Found.class);
            List<PaymentTransaction> found = ((PaymentTransactionsLookup.Found) lookup).transactions();
            long amount = ((Number) orderRow(order.orderId()).get("amount")).longValue();
            boolean pgSuccessWithSameAmount = found.size() == 1
                && found.get(0).status() == PaymentTransactionStatus.SUCCESS
                && found.get(0).amount().value() == amount
                && found.get(0).providerOrderId().equals(order.providerOrderId());
            boolean pgFailedOrMissing = found.isEmpty()
                || found.stream().anyMatch(transaction -> transaction.status() == PaymentTransactionStatus.FAILED);

            List<ILoggingEvent> orderLogs = logsOf(order.orderId());
            List<ILoggingEvent> unknownWarns = createResults(orderLogs, "Unknown").stream()
                .filter(event -> event.getLevel() == Level.WARN).toList();
            long timeoutUnknown = unknownWarns.stream()
                .filter(event -> arguments(event).stream().anyMatch(argument -> argument.contains("HttpTimeoutException")))
                .count();
            long applyErrors = orderLogs.stream().filter(event -> event.getLevel() == Level.ERROR).count();
            long accepted = createResults(orderLogs, "Accepted").size();
            long verified = orderLogs.stream()
                .filter(event -> event.getFormattedMessage().contains(LOOKUP_JUDGMENT))
                .filter(event -> arguments(event).contains("Verified"))
                .count();
            long notSent = createResults(orderLogs, "NotSent").size();

            boolean h1 = unknownWarns.size() == 1 && timeoutUnknown == 1 && applyErrors == 0 && pgSuccessWithSameAmount;
            boolean h2 = (accepted + verified) > 0 && applyErrors > 0;
            boolean h3 = pgFailedOrMissing || notSent > 0;
            assertThat(h1).as("H1 응답 유실 채택 order=%d", order.orderId()).isTrue();
            assertThat(h2).as("H2 내부 반영 실패 기각 order=%d", order.orderId()).isFalse();
            assertThat(h3).as("H3 PG 실패·미발송 기각 order=%d", order.orderId()).isFalse();

            Map<String, Object> observed = new LinkedHashMap<String, Object>();
            observed.put("createUnknownWarn", unknownWarns.size());
            observed.put("createUnknownWarnWithHttpTimeoutException", timeoutUnknown);
            observed.put("createAcceptedInfo", accepted);
            observed.put("createNotSentWarn", notSent);
            observed.put("lookupVerifiedInfo", verified);
            observed.put("errorLines", applyErrors);
            observed.put("pgLookup", found.stream().map(CheckoutIncidentE2ETest::transactionMap).toList());
            observed.put("orderAmount", amount);
            Map<String, Object> judgment = new LinkedHashMap<String, Object>();
            judgment.put("orderId", order.orderId());
            judgment.put("providerOrderId", order.providerOrderId());
            judgment.put("observed", observed);
            List<Map<String, Object>> hypotheses = List.of(
                hypothesis("H1", "응답 유실: PG는 성공했지만 create 응답을 받지 못했다",
                    "create Unknown WARN(HttpTimeoutException) 1, 반영 ERROR 0, PG 조회 SUCCESS·금액 일치", h1),
                hypothesis("H2", "내부 반영 실패: PG 결과를 받거나 조회했지만 우리 DB 반영이 실패했다",
                    "create Accepted INFO 또는 조회 Verified INFO가 있고 반영 ERROR가 있다", h2),
                hypothesis("H3", "PG 실패·미발송: 돈이 움직이지 않았다",
                    "PG 거래가 없거나 FAILED, 또는 create NotSent WARN", h3));
            for (Map<String, Object> hypothesis : hypotheses) {
                String bucket = "ADOPTED".equals(hypothesis.get("verdict")) ? "adopted" : "rejected";
                verdict.get(bucket).computeIfAbsent((String) hypothesis.get("id"), id -> new ArrayList<Long>())
                    .add(order.orderId());
            }
            judgment.put("hypotheses", hypotheses);
            judgment.put("boundaryLogs", orderLogs.stream().map(CheckoutIncidentE2ETest::logMap).toList());
            judgments.add(judgment);
        }
        assertThat(ledger()).as("진단 조회 전후 commerce 상태가 같다").isEqualTo(before);
        assertThat(server.createCalls()).as("진단은 create를 보내지 않는다").isEqualTo(createsBefore);
        List<Long> lostIds = set("fault-lost").stream().map(TrackedOrder::orderId).toList();
        assertThat(verdict).as("판정 요약").isEqualTo(Map.of(
            "adopted", Map.of("H1", lostIds),
            "rejected", Map.of("H2", lostIds, "H3", lostIds)));

        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("faultSha256", faultSha256);
        extra.put("diagnosisMethod", "TestPgFixture.newClient().verifyOrder(providerOrderId, null) + MDC orderId로 거른 경계 로그");
        extra.put("verdict", verdict);
        extra.put("judgments", judgments);
        evidence.write("diagnosis", stage("DIAGNOSIS", scrape(managementPort), extra));
    }

    // S5 RECOVERY: 유실 지정을 풀고 없음을 확인한 뒤, 사용자 2가 같은 3건에 본인 reconcile HTTP를 보낸다.
    private void recover(String faultSha256) throws Exception {
        clock.set(RECOVERY_AT);
        for (TrackedOrder order : set("fault-lost")) {
            testPgFixture.clearOrderBehavior(order.providerOrderId());
            armedLosses.remove(order.providerOrderId());
        }
        assertThat(server.lostResponseOrderIds()).as("복구 전 응답 유실 지정이 없다").isEmpty();
        assertThat(evidence.sha256("fault")).isEqualTo(faultSha256);
        long createsBefore = server.createCalls();
        double confirmedBefore = confirmedCounter(scrape(managementPort));
        Map<Long, Map<String, Object>> ledgerBefore = ledger();

        for (TrackedOrder order : set("fault-lost")) {
            HttpResult reconciled = reconcile(order.orderId(), FAULT_USER);
            assertThat(reconciled.status()).isEqualTo(200);
            assertThat(json(reconciled).at("/data/status").asText()).isEqualTo("CONFIRMED");
        }

        List<TrackedOrder> faultSet = faultSet();
        assertThat(faultSet).allSatisfy(order -> assertThat(orderRow(order.orderId()))
            .containsEntry("status", "CONFIRMED").containsEntry("provider_status", "SUCCESS"));
        assertPgSuccessOncePerOrder(faultSet);
        assertThat(server.createCalls()).as("복구는 create를 다시 보내지 않는다").isEqualTo(createsBefore);
        assertThat(tracked).allSatisfy(order -> assertThat(server.createCalls(order.providerOrderId())).isEqualTo(1));
        assertThat(server.transactions()).hasSize(2 * ORDERS_PER_SET);
        assertThat(availableStock()).as("재고 추가 차감 없음").isEqualTo(STOCK - 2 * ORDERS_PER_SET);
        assertPointsAndCouponsUnchanged();
        assertThat(tracked).allSatisfy(order -> assertThat(outboxRows(order.orderId())).as("Outbox order=%d", order.orderId())
            .isEqualTo(1));
        assertThat(jdbc.queryForObject("select count(*) from order_outbox", Long.class)).isEqualTo(2L * ORDERS_PER_SET);
        String prometheus = refreshedPrometheus();
        assertThat(confirmedCounter(prometheus) - confirmedBefore).as("outcome confirmed 증가").isEqualTo(LOST);
        assertUnresolved(prometheus, Map.of("send_started", 0.0, "pending", 0.0, "unknown", 0.0, "needs_review", 0.0));
        Instant weekStart = WEEK.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant weekEnd = WEEK.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(set("fault-lost")).allSatisfy(order -> {
            Instant confirmedAt = Instant.parse(confirmedAt(order.orderId()));
            assertThat(confirmedAt).isEqualTo(RECOVERY_AT).isAfterOrEqualTo(weekStart).isBefore(weekEnd);
        });

        // 복구는 유실 3건만 바꾼다. 나머지 17건(기준선 10·장애 확정 7)의 상태·confirmedAt·Outbox는 복구 전 장부와 같다.
        Map<Long, Map<String, Object>> ledgerAfter = ledger();
        assertThat(ledgerAfter.keySet()).as("장부 주문 집합").isEqualTo(ledgerBefore.keySet());
        assertThat(tracked).hasSize(2 * ORDERS_PER_SET).filteredOn(order -> !order.lost()).allSatisfy(order ->
            assertThat(ledgerAfter.get(order.orderId())).as("복구 대상이 아닌 주문 order=%d", order.orderId())
                .isEqualTo(ledgerBefore.get(order.orderId())));
        assertThat(ledgerAfter.get(0L)).as("가용 수량").isEqualTo(ledgerBefore.get(0L));
        Map<Long, Instant> expectedConfirmedAt = new LinkedHashMap<Long, Instant>();
        for (TrackedOrder order : tracked) {
            expectedConfirmedAt.put(order.orderId(), switch (order.set()) {
                case "normal" -> BASELINE_AT;
                case "fault-confirmed" -> FAULT_AT;
                default -> RECOVERY_AT;
            });
        }
        Map<Long, Instant> actualConfirmedAt = new LinkedHashMap<Long, Instant>();
        tracked.forEach(order -> actualConfirmedAt.put(order.orderId(), confirmedInstant(order.orderId())));
        assertThat(actualConfirmedAt).as("20건 confirmedAt: 기준선 BASELINE_AT, 장애 확정 FAULT_AT, 유실 RECOVERY_AT")
            .isEqualTo(expectedConfirmedAt);
        confirmedAtAfterRecovery = confirmedAtOfAll();

        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("faultSha256", faultSha256);
        extra.put("createCallsBeforeRecovery", createsBefore);
        extra.put("pointsAtStart", pointsAtStart);
        extra.put("couponsAtStart", couponsAtStart);
        evidence.write("recovery", stage("RECOVERY", prometheus, extra));
    }

    // S6 반복·보호: 같은 3건에 reconcile 한 번 더 + callback 한 번, 사용자 3이 남의 주문 1건 reconcile.
    private void repeatAndProtect() throws Exception {
        clock.set(REPEAT_AT);
        Map<Long, Map<String, Object>> before = ledger();
        long createsBefore = server.createCalls();
        double confirmedBefore = confirmedCounter(scrape(managementPort));

        for (TrackedOrder order : set("fault-lost")) {
            HttpResult again = reconcile(order.orderId(), FAULT_USER);
            assertThat(again.status()).isEqualTo(200);
            assertThat(json(again).at("/data/status").asText()).isEqualTo("CONFIRMED");
        }
        TrackedOrder firstLost = set("fault-lost").get(0);
        assertThat(callback(firstLost).status()).isEqualTo(200);

        long createsBeforeOther = server.createCalls();
        long lookupsBeforeOther = server.lookupCalls();
        HttpResult other = reconcile(firstLost.orderId(), OTHER_USER);
        assertThat(other.status()).as("다른 사용자의 reconcile은 거절된다").isEqualTo(404);
        assertThat(server.createCalls()).isEqualTo(createsBeforeOther);
        assertThat(server.lookupCalls()).as("거절은 PG를 부르기 전이다").isEqualTo(lookupsBeforeOther);

        assertThat(ledger()).as("반복 reconcile·callback 뒤 상태·Outbox·confirmedAt 변화 없음").isEqualTo(before);
        assertThat(server.createCalls()).isEqualTo(createsBefore);
        assertThat(availableStock()).isEqualTo(STOCK - 2 * ORDERS_PER_SET);
        String prometheus = refreshedPrometheus();
        assertThat(confirmedCounter(prometheus)).as("outcome confirmed 변화 없음").isEqualTo(confirmedBefore);
        assertUnresolved(prometheus, Map.of("send_started", 0.0, "pending", 0.0, "unknown", 0.0, "needs_review", 0.0));
        assertThat(confirmedAtOfAll()).isEqualTo(confirmedAtAfterRecovery);

        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("otherUserReconcile", Map.of("orderId", firstLost.orderId(), "userId", OTHER_USER, "httpStatus", other.status(),
            "createCallsDelta", server.createCalls() - createsBeforeOther));
        evidence.write("repeat", stage("REPEAT", prometheus, extra));
    }

    // S7 후속: relay → Kafka → streamer 구매 활동 → batch 주간 랭킹 → 랭킹 API. 활동은 주문·상품마다 1번, 점수는 전체 입력 20이다.
    private void followUp() throws Exception {
        clock.set(FOLLOWUP_AT);
        MessageListenerContainer listener = streamer.getBean(KafkaListenerEndpointRegistry.class)
            .getListenerContainer(OrderEventsListener.LISTENER_ID);
        awaitUntil("consumer topic partition 배정", ASSIGNMENT_DEADLINE, () -> assignedToTopic(listener),
            () -> "assigned=" + listener.getAssignedPartitions());
        HttpResult notPublished = weeklyRanking();
        assertThat(json(notPublished).at("/data/status").asText()).as("이 주의 기존 공개본이 없다").isEqualTo("NOT_PUBLISHED");

        orderOutboxRelay.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from order_outbox where status <> 'PUBLISHED'", Long.class)).isZero();
        awaitUntil("구매 활동 20건 기록", RESULT_DEADLINE,
            () -> tracked.stream().allMatch(order -> recordedOnce(order.orderId())),
            () -> "consumed=" + ConsumeObservation.CONSUMED);

        assertThat(tracked).allSatisfy(order -> {
            List<Map<String, Object>> rows = activityRows(order.orderId());
            assertThat(rows).as("구매 활동 order=%d", order.orderId()).hasSize(1);
            assertThat(((Number) rows.get(0).get("product_id")).longValue()).isEqualTo(productId);
            assertThat(((Number) rows.get(0).get("quantity")).intValue()).isEqualTo(1);
        });
        List<Consumed> deliveries = ourDeliveries();
        assertThat(deliveries.size()).as("수신 시도 수").isGreaterThanOrEqualTo(2 * ORDERS_PER_SET);
        assertThat(deliveries).filteredOn(entry -> entry.result() == ConsumeResult.RECORDED).hasSize(2 * ORDERS_PER_SET);

        JobExecution execution = batch.getBean(WeeklyRankingLauncher.class).launch(WEEK, null);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        String snapshotId = "weekly-" + WEEK.toString().replace("-", "") + "-r1";
        assertThat(execution.getJobParameters().getString("snapshotId")).isEqualTo(snapshotId);
        HttpResult ranking = weeklyRanking();
        JsonNode rankingData = json(ranking).path("data");
        assertThat(rankingData.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(rankingData.path("snapshotId").asText()).isEqualTo(snapshotId);
        assertThat(rankingData.path("items")).hasSize(1);
        assertThat(rankingData.path("items").get(0).path("productId").asLong()).isEqualTo(productId);
        assertThat(rankingData.path("items").get(0).path("score").asLong()).as("랭킹 점수").isEqualTo(2L * ORDERS_PER_SET);
        assertThat(jdbc.queryForList("select snapshot_id from ranking_publication where period_start = ?", String.class, WEEK))
            .containsExactly(snapshotId);
        assertThat(confirmedAtOfAll()).as("confirmedAt은 최초 확정 값 그대로다").isEqualTo(confirmedAtAfterRecovery);

        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("activityRows", jdbc.queryForList("select order_id, product_id, quantity from product_purchase_activity"
            + " order by order_id"));
        extra.put("deliveries", deliveries.stream()
            .map(entry -> Map.of("eventId", entry.eventId(), "offset", entry.offset(), "result", entry.result().name())).toList());
        extra.put("deliveryCount", deliveries.size());
        extra.put("rankingSnapshotId", snapshotId);
        extra.put("rankingBeforeLaunchRaw", notPublished.body());
        extra.put("rankingRaw", ranking.body());
        evidence.write("followup", stage("FOLLOWUP", scrape(managementPort), extra));
    }

    // 자기가 켠 것만 푼다: 남은 응답 유실 지정, 로그 appender·레벨, streamer·batch 컨텍스트. 거래·증거는 지우지 않는다.
    // 한 단계가 실패해도 나머지 단계는 돈다. 정리 예외는 모아서, 본 실패가 있으면 그 suppressed로 붙이고 없으면 던진다.
    private void releaseOwnInjections(Throwable primary) {
        List<Throwable> errors = new ArrayList<Throwable>();
        releaseStep(errors, () -> {
            if (pgStarted) {
                for (String providerOrderId : List.copyOf(armedLosses)) {
                    testPgFixture.clearOrderBehavior(providerOrderId);
                }
            }
            armedLosses.clear();
        });
        releaseStep(errors, this::detachLogCapture);
        releaseStep(errors, () -> {
            if (streamer != null) {
                streamer.close();
            }
        });
        releaseStep(errors, () -> {
            if (batch != null) {
                batch.close();
            }
        });
        if (errors.isEmpty()) {
            return;
        }
        if (primary != null) {
            errors.forEach(primary::addSuppressed);
            return;
        }
        IllegalStateException released = new IllegalStateException("사고 테스트 정리 실패 " + errors.size() + "건", errors.get(0));
        errors.subList(1, errors.size()).forEach(released::addSuppressed);
        throw released;
    }

    private static void releaseStep(List<Throwable> errors, Runnable step) {
        try {
            step.run();
        } catch (Throwable error) {
            errors.add(error);
        }
    }

    private Map<String, Object> stage(String name, String prometheusRaw, Map<String, Object> extra) throws Exception {
        HttpResult health = health();
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("runId", evidence.runId());
        content.put("stage", name);
        content.put("observedAt", Instant.now().toString());
        content.put("testClock", clock.instant().toString());
        content.put("orders", tracked.stream().map(this::orderEvidence).toList());
        content.put("pg", Map.of("createCalls", server.createCalls(), "lookupCalls", server.lookupCalls(),
            "transactions", server.transactions().stream().map(CheckoutIncidentE2ETest::storedMap).toList()));
        content.put("availableStock", availableStock());
        content.put("healthStatus", health.status());
        content.put("healthRaw", health.body());
        content.put("prometheusRaw", prometheusRaw);
        content.put("boundaryLogs", newBoundaryLogs());
        content.putAll(extra);
        assertNoCardNumber(objectMapper.writeValueAsString(content), name);
        return content;
    }

    private Map<String, Object> orderEvidence(TrackedOrder order) {
        Map<String, Object> row = orderRow(order.orderId());
        Map<String, Object> saved = new LinkedHashMap<String, Object>();
        saved.put("set", order.set());
        saved.put("orderId", order.orderId());
        saved.put("userId", order.userId());
        saved.put("attemptKey", row.get("attempt_key"));
        saved.put("providerOrderId", row.get("provider_order_id"));
        saved.put("transactionKey", row.get("transaction_key"));
        saved.put("status", row.get("status"));
        saved.put("providerStatus", row.get("provider_status"));
        saved.put("paymentResult", row.get("result"));
        saved.put("confirmedAt", row.get("confirmed_at"));
        saved.put("createCalls", server.createCalls(order.providerOrderId()));
        saved.put("pgTransactions", pgTransactions(order).stream().map(CheckoutIncidentE2ETest::storedMap).toList());
        saved.put("outboxRows", outboxRows(order.orderId()));
        return saved;
    }

    // 지난 단계 기록 뒤 새로 쌓인 경계 로그 중 이 테스트 주문(MDC orderId)의 것만 남긴다.
    private List<Map<String, Object>> newBoundaryLogs() {
        List<ILoggingEvent> all = List.copyOf(logs.list);
        List<ILoggingEvent> fresh = all.subList(logCursor, all.size());
        logCursor = all.size();
        Set<String> orderIds = Set.copyOf(tracked.stream().map(order -> String.valueOf(order.orderId())).toList());
        return fresh.stream()
            .filter(event -> orderIds.contains(event.getMDCPropertyMap().get("orderId")))
            .map(CheckoutIncidentE2ETest::logMap)
            .toList();
    }

    private TrackedOrder placeOrder(String set, long userId) throws Exception {
        String body = "{\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}";
        HttpResult created = send("POST", "/api/v1/orders", userId, body);
        assertThat(created.status()).as("주문 생성").isEqualTo(201);
        long orderId = json(created).at("/data/id").asLong();
        TrackedOrder order = new TrackedOrder(set, orderId, userId, UUID.randomUUID().toString(),
            String.format("LP-ORD-%010d", orderId));
        tracked.add(order);
        return order;
    }

    private HttpResult confirm(TrackedOrder order) throws Exception {
        String body = "{\"paymentAttemptKey\":\"" + order.attemptKey() + "\",\"cardType\":\"" + TestPgFixture.CARD_TYPE
            + "\",\"cardNo\":\"" + TestPgFixture.CARD_NO + "\"}";
        return send("POST", "/api/v1/orders/" + order.orderId() + "/confirm", order.userId(), body);
    }

    private HttpResult reconcile(long orderId, long userId) throws Exception {
        return send("POST", "/api/v1/orders/" + orderId + "/payment/reconcile", userId, null);
    }

    private HttpResult callback(TrackedOrder order) throws Exception {
        String body = "{\"transactionKey\":\"" + storedTransactionKey(order.orderId()) + "\",\"orderId\":\""
            + order.providerOrderId() + "\",\"status\":\"SUCCESS\"}";
        return send("POST", "/api/v1/payments/callback", null, body);
    }

    private HttpResult weeklyRanking() throws Exception {
        HttpResult result = send("GET", "/api/v1/rankings/weekly?periodStart=" + WEEK + "&limit=10", null, null);
        assertThat(result.status()).isEqualTo(200);
        return result;
    }

    private JsonNode points(long userId) throws Exception {
        HttpResult result = send("GET", "/api/v1/points", userId, null);
        assertThat(result.status()).isEqualTo(200);
        return json(result).path("data");
    }

    private JsonNode coupons(long userId) throws Exception {
        HttpResult result = send("GET", "/api/v1/coupons", userId, null);
        assertThat(result.status()).isEqualTo(200);
        return json(result).path("data");
    }

    private void assertPointsAndCouponsUnchanged() throws Exception {
        for (long userId : List.of(NORMAL_USER, FAULT_USER)) {
            assertThat(points(userId)).as("포인트 user=%d", userId).isEqualTo(pointsAtStart.get(userId));
            assertThat(coupons(userId)).as("쿠폰 user=%d", userId).isEqualTo(couponsAtStart.get(userId));
        }
    }

    private HttpResult health() throws Exception {
        return get(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health"));
    }

    private void assertHealthUp() throws Exception {
        HttpResult health = health();
        assertThat(health.status()).as("health 응답 코드").isEqualTo(200);
        assertThat(json(health).path("status").asText()).as("health").isEqualTo("UP");
    }

    private String refreshedPrometheus() throws Exception {
        unresolvedPaymentMetrics.refresh();
        return scrape(managementPort);
    }

    private static void assertUnresolved(String prometheus, Map<String, Double> expected) {
        for (String status : UNRESOLVED_STATUSES) {
            Optional<Double> value = unresolved(prometheus, status);
            assertThat(value).as("unresolved status=%s 시계열", status).isPresent();
            assertThat(value.get()).as("unresolved status=%s", status).isEqualTo(expected.get(status));
        }
    }

    private static double confirmedCounter(String prometheus) {
        return outcome(prometheus, "confirmed").orElse(0.0);
    }

    private HttpResult send(String method, String path, Long userId, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(HTTP_TIMEOUT);
        if (userId != null) {
            builder.header("X-USER-ID", String.valueOf(userId));
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.body());
    }

    private HttpResult get(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.body());
    }

    private JsonNode json(HttpResult result) throws Exception {
        return objectMapper.readTree(result.body());
    }

    private void assertProviderOrderIdAsPredicted(TrackedOrder order) {
        assertThat(orderRow(order.orderId()).get("provider_order_id")).as("규칙으로 계산한 providerOrderId order=%d", order.orderId())
            .isEqualTo(order.providerOrderId());
        assertThat(orderRow(order.orderId()).get("attempt_key")).isEqualTo(order.attemptKey());
    }

    private void assertPgSuccessOncePerOrder(List<TrackedOrder> orders) {
        assertThat(orders).allSatisfy(order -> {
            assertThat(pgTransactions(order)).as("PG 거래 order=%d", order.orderId()).hasSize(1)
                .allSatisfy(transaction -> assertThat(transaction.status()).isEqualTo("SUCCESS"));
            assertThat(server.createCalls(order.providerOrderId())).as("create order=%d", order.orderId()).isEqualTo(1);
        });
    }

    private List<StoredTransaction> pgTransactions(TrackedOrder order) {
        return server.transactions().stream()
            .filter(transaction -> transaction.orderId().equals(order.providerOrderId()))
            .toList();
    }

    private List<TrackedOrder> set(String name) {
        return tracked.stream().filter(order -> order.set().equals(name)).toList();
    }

    private List<TrackedOrder> faultSet() {
        return tracked.stream().filter(order -> order.userId() == FAULT_USER).toList();
    }

    private Map<String, Object> orderRow(long orderId) {
        return jdbc.queryForMap("select o.status, o.user_id, date_format(o.confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ') as confirmed_at,"
            + " p.attempt_key, p.provider_order_id, p.transaction_key, p.provider_status, p.result, p.amount"
            + " from orders o join payment_records p on p.order_id = o.id where o.id = ?", orderId);
    }

    private String confirmedAt(long orderId) {
        return (String) orderRow(orderId).get("confirmed_at");
    }

    private Instant confirmedInstant(long orderId) {
        return Instant.parse(confirmedAt(orderId));
    }

    private Map<Long, String> confirmedAtOfAll() {
        Map<Long, String> confirmedAts = new LinkedHashMap<Long, String>();
        tracked.forEach(order -> confirmedAts.put(order.orderId(), confirmedAt(order.orderId())));
        return confirmedAts;
    }

    // 경계 비교용 장부: 주문·결제 기록 상태, Outbox 행 수, 가용 수량.
    private Map<Long, Map<String, Object>> ledger() {
        Map<Long, Map<String, Object>> ledger = new LinkedHashMap<Long, Map<String, Object>>();
        for (TrackedOrder order : tracked) {
            Map<String, Object> row = new LinkedHashMap<String, Object>(orderRow(order.orderId()));
            row.put("outbox_rows", outboxRows(order.orderId()));
            ledger.put(order.orderId(), row);
        }
        ledger.put(0L, Map.of("available_stock", availableStock()));
        return ledger;
    }

    private String storedTransactionKey(long orderId) {
        return jdbc.queryForObject("select transaction_key from payment_records where order_id = ?", String.class, orderId);
    }

    private int availableStock() {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    private long outboxRows(long orderId) {
        return jdbc.queryForObject("select count(*) from order_outbox where order_id = ?", Long.class, orderId);
    }

    private List<Map<String, Object>> activityRows(long orderId) {
        return jdbc.queryForList("select product_id, quantity from product_purchase_activity where order_id = ?", orderId);
    }

    private boolean recordedOnce(long orderId) {
        return deliveriesOf(orderId).stream().anyMatch(entry -> entry.result() == ConsumeResult.RECORDED);
    }

    private List<Consumed> ourDeliveries() {
        Set<String> eventIds = Set.copyOf(tracked.stream().map(order -> eventId(order.orderId())).toList());
        return ConsumeObservation.CONSUMED.stream().filter(entry -> eventIds.contains(entry.eventId())).toList();
    }

    private List<Consumed> deliveriesOf(long orderId) {
        return ConsumeObservation.CONSUMED.stream().filter(entry -> eventId(orderId).equals(entry.eventId())).toList();
    }

    private static String eventId(long orderId) {
        return "order-confirmed:v1:" + orderId;
    }

    private List<ILoggingEvent> logsOf(long orderId) {
        String key = String.valueOf(orderId);
        return List.copyOf(logs.list).stream()
            .filter(event -> key.equals(event.getMDCPropertyMap().get("orderId")))
            .toList();
    }

    private static List<ILoggingEvent> createResults(List<ILoggingEvent> events, String result) {
        return events.stream()
            .filter(event -> event.getLoggerName().equals(SERVICE_LOGGER))
            .filter(event -> event.getFormattedMessage().contains(CREATE_RESULT))
            .filter(event -> arguments(event).contains(result))
            .toList();
    }

    private static List<String> arguments(ILoggingEvent event) {
        Object[] arguments = event.getArgumentArray();
        if (arguments == null) {
            return List.of();
        }
        return Arrays.stream(arguments).map(String::valueOf).toList();
    }

    private static Map<String, Object> hypothesis(String id, String statement, String expectedEvidence, boolean holds) {
        Map<String, Object> hypothesis = new LinkedHashMap<String, Object>();
        hypothesis.put("id", id);
        hypothesis.put("statement", statement);
        hypothesis.put("expectedEvidence", expectedEvidence);
        hypothesis.put("verdict", holds ? "ADOPTED" : "REJECTED");
        return hypothesis;
    }

    private static Map<String, Object> logMap(ILoggingEvent event) {
        Map<String, Object> log = new LinkedHashMap<String, Object>();
        log.put("at", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        log.put("level", event.getLevel().toString());
        log.put("logger", event.getLoggerName());
        log.put("message", event.getFormattedMessage());
        log.put("mdc", Map.copyOf(event.getMDCPropertyMap()));
        return log;
    }

    private static Map<String, Object> storedMap(StoredTransaction transaction) {
        return Map.of("transactionKey", transaction.transactionKey(), "providerOrderId", transaction.orderId(),
            "amount", transaction.amount(), "status", transaction.status());
    }

    private static Map<String, Object> transactionMap(PaymentTransaction transaction) {
        return Map.of("transactionKey", transaction.transactionKey(), "providerOrderId", transaction.providerOrderId(),
            "amount", transaction.amount().value(), "status", transaction.status().name());
    }

    private static JsonNode savedOrder(JsonNode stage, long orderId) {
        for (JsonNode order : stage.path("orders")) {
            if (order.path("orderId").asLong() == orderId) {
                return order;
            }
        }
        return fail("증거 파일에 주문이 없습니다. order=" + orderId);
    }

    private static void assertNoCardNumber(String text, String source) {
        assertThat(text).as("카드번호 없음: %s", source).doesNotContain(TestPgFixture.CARD_NO).doesNotContain(CARD_DIGITS);
    }

    private void deleteOwnRankingRows() {
        String prefix = "weekly-" + WEEK.toString().replace("-", "") + "-r%";
        jdbc.update("delete from ranking_publication where period_start = ?", WEEK);
        jdbc.update("delete from ranking_result where snapshot_id like ?", prefix);
        jdbc.update("delete from ranking_score_stage where snapshot_id like ?", prefix);
        jdbc.update("delete from ranking_snapshot_input where snapshot_id like ?", prefix);
        jdbc.update("delete from ranking_snapshot where snapshot_id like ?", prefix);
    }

    private void attachLogCapture() {
        logs.start();
        serviceLevel = logger(SERVICE_LOGGER).getLevel();
        adapterLevel = logger(ADAPTER_LOGGER).getLevel();
        logger(SERVICE_LOGGER).setLevel(Level.INFO);
        logger(ADAPTER_LOGGER).setLevel(Level.INFO);
        logger(SERVICE_LOGGER).addAppender(logs);
        logger(ADAPTER_LOGGER).addAppender(logs);
    }

    private void detachLogCapture() {
        logger(SERVICE_LOGGER).detachAppender(logs);
        logger(ADAPTER_LOGGER).detachAppender(logs);
        logger(SERVICE_LOGGER).setLevel(serviceLevel);
        logger(ADAPTER_LOGGER).setLevel(adapterLevel);
        logs.stop();
    }

    private static Logger logger(String name) {
        return (Logger) LoggerFactory.getLogger(name);
    }

    private void awaitUntil(String step, Duration limit, BooleanSupplier condition, Supplier<String> state) {
        Instant deadline = Instant.now().plus(limit);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                fail(step + " 대기 " + limit.toSeconds() + "초 초과. 실제 상태: " + state.get());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(step + " 대기 중 인터럽트. 실제 상태: " + state.get());
            }
        }
    }

    private static boolean assignedToTopic(MessageListenerContainer listener) {
        Collection<TopicPartition> assigned = listener.getAssignedPartitions();
        return assigned != null && assigned.stream().anyMatch(partition -> TOPIC.equals(partition.topic()));
    }

    // 이벤트의 MDC는 처음 읽을 때 현재 스레드에서 가져오므로, 기록 시점의 값을 남기려고 받는 즉시 고정한다.
    private static final class SnapshotAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}
