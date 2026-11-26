package com.loopers.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponSeedService;
import com.loopers.application.payment.ProviderOrderSuffixGenerator;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxRelay;
import com.loopers.support.incident.CapturedLogs;
import com.loopers.support.incident.ChildApp;
import com.loopers.support.incident.IncidentEvidence;
import com.loopers.support.testpg.TestPgContainer;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.support.time.MutableClock;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

// 결제 응답 유실 사고를 한 테스트 lifecycle에서 재현하고 같은 주문을 추가 결제 없이 복구한다.
// baseline → fault → 증거 보존 → recovery → 수렴 확인 순서이고, 단계와 기대값은 설계 문서의 사고 실험을 따른다.
// 테스트에서는 metric export가 꺼져 /actuator/prometheus가 없으므로 켠다.
@Tag("incident")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@AutoConfigureObservability
class CheckoutIncidentTest {
    private static final Instant T = Instant.parse("2026-11-11T03:00:00Z");
    private static final LocalDate PERIOD_START = LocalDate.parse("2026-11-09");
    private static final long PRICE = 7_000L;
    private static final int INITIAL_STOCK = 30;
    private static final long POINTS = 100_000L;
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final List<Integer> FAULT_POSITIONS = List.of(2, 5, 8);
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String TOPIC = "incident-order-confirmed-" + UUID.randomUUID();
    private static final String GROUP = "incident-purchase-activity-" + UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @DynamicPropertySource
    static void incidentProperties(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> 0);
        registry.add("management.server.address", () -> "127.0.0.1");
        registry.add("payment.pg.base-url", TestPgContainer::baseUrl);
        // 테스트 PG의 callback이 어느 주문에서도 도착하지 않도록 닿지 않는 주소를 넘긴다. 정상 주문은 본인 reconcile로 확정한다.
        registry.add("payment.pg.callback-url", () -> "http://127.0.0.1:1/api/v1/payments/callback");
        registry.add("payment.attempt-metrics.enabled", () -> true);
        registry.add("payment.attempt-metrics.interval", () -> "200ms");
        registry.add("order.confirmed-event.topic", () -> TOPIC);
    }

    @TestConfiguration
    static class IncidentClock {
        @Bean
        @Primary
        MutableClock incidentClock() {
            return new MutableClock(T, ZoneOffset.UTC);
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private PointRepository pointRepository;
    @Autowired private CouponSeedService couponSeedService;
    @Autowired private CouponIssueAdmissionState couponAdmission;
    @Autowired private OrderConfirmedOutboxRelay relay;
    @Autowired private MutableClock clock;
    @MockitoBean private ProviderOrderSuffixGenerator suffixGenerator;
    @LocalManagementPort private int managementPort;
    @Value("${payment.pg.read-timeout}") private Duration responseTimeout;

    private final AtomicReference<String> nextSuffix = new AtomicReference<>();
    private final Map<String, Object> manifest = new LinkedHashMap<>();
    private final List<String> faultFiles = new ArrayList<>();
    private final List<OrderRef> createdOrders = new ArrayList<>();
    // 이 실행이 테스트 PG에 등록해 아직 해제하지 않은 응답 유실 주입이다. PG 주문 식별자 → 시나리오.
    private final Map<String, String> activeInjections = new LinkedHashMap<>();
    private IncidentEvidence evidence;
    @LocalServerPort private int port;
    private ChildApp.Infra infra;
    private long productId;

    @DisplayName("PG 성공 뒤 생성 응답을 잃은 주문 3건을 증거로 진단하고, 추가 결제 없이 같은 주문을 복구해 주간 랭킹까지 반영한다.")
    @Test
    void recoversLostSuccessResponsesWithoutPayingAgain() throws Exception {
        evidence = IncidentEvidence.create(Path.of(System.getProperty("incident.evidence-dir")), Instant.now());
        manifest.put("runId", evidence.runId());
        manifest.put("startedAt", Instant.now().toString());
        manifest.put("businessClock", Map.of("baseline", T.toString(), "fault", T.plusSeconds(3_600).toString(),
            "recovery", T.plusSeconds(7_200).toString(), "convergence", T.plusSeconds(10_800).toString()));
        Map<String, Long> childPids = new LinkedHashMap<>();
        manifest.put("childProcesses", childPids);
        infra = ChildApp.Infra.fromTestContainers();
        when(suffixGenerator.next()).thenAnswer(invocation -> nextSuffix.get());
        String phase = "setup";
        ChildApp streamer = null;
        CapturedLogs logs = new CapturedLogs();
        boolean logsWritten = false;
        Throwable primaryFailure = null;
        try {
            createStreamerTables();
            createTopic();
            streamer = ChildApp.start("commerce-streamer", Path.of(System.getProperty("incident.streamer-jar")),
                ChildApp.streamerArguments(infra, TOPIC, GROUP), evidence.path("logs/commerce-streamer.log"));
            childPids.put("commerce-streamer", streamer.pid());
            awaitConsumerAssigned(streamer);
            long userA = userRepository.save(User.create()).getId();
            long userB = userRepository.save(User.create()).getId();
            long userC = userRepository.save(User.create()).getId();
            productId = createProduct();
            prepareWallets(userA, userB);
            manifest.put("users", Map.of("A", userA, "B", userB, "C", userC));
            manifest.put("productId", productId);

            phase = "baseline";
            List<OrderRef> baseline = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                baseline.add(createOrder("baseline", userA, false));
            }
            assertNoActiveInjection();
            for (OrderRef order : baseline) {
                assertThat(confirm(order).path("attemptStatus").asText()).isEqualTo("PENDING");
            }
            for (OrderRef order : baseline) {
                assertThat(TestPgControl.complete(order.providerOrderId())).isEqualTo(1);
                confirmByReconcile(order);
            }
            publishAndAwaitActivities(10);
            assertPhase(baseline, new PhaseExpectation(10, 0, 0, 0, 10, 10, 20, 0, 0D, 0D));
            writePhase("baseline", baseline, List.of());
            assertThat(managementStatus("/actuator/env")).as("env는 열지 않는다").isEqualTo(404);
            assertThat(managementStatus("/actuator/heapdump")).as("heapdump는 열지 않는다").isEqualTo(404);

            phase = "fault";
            clock.set(T.plusSeconds(3_600));
            List<OrderRef> fault = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                fault.add(createOrder("fault", userB, FAULT_POSITIONS.contains(index)));
            }
            List<OrderRef> lost = fault.stream().filter(OrderRef::lost).toList();
            for (OrderRef order : lost) {
                TestPgControl.scenario(order.providerOrderId(), "{\"scenario\":\"DROP_RESPONSE\"}");
                activeInjections.put(order.providerOrderId(), "DROP_RESPONSE");
            }
            faultFiles.add("raw/fault-injected-orders.json");
            evidence.writeJson("raw/fault-injected-orders.json", lost.stream().map(OrderRef::providerOrderId).toList());
            faultFiles.add("raw/fault-injection-state.json");
            evidence.writeJson("raw/fault-injection-state.json", injectionState());
            // 장애 주문은 PG가 응답을 늦춰 UNKNOWN이 된다. 늦춘 응답이 나가기 전에 그 거래를 SUCCESS로 완료한다.
            for (OrderRef order : fault) {
                String expected = order.lost() ? "UNKNOWN" : "PENDING";
                assertThat(confirm(order).path("attemptStatus").asText()).isEqualTo(expected);
                if (order.lost()) {
                    assertThat(TestPgControl.complete(order.providerOrderId())).isEqualTo(1);
                }
            }
            for (OrderRef order : lost) {
                assertThat(orderDetail(order.userId(), order.orderId()).path("data").path("payment")
                    .path("paymentProgress").asText()).isEqualTo("UNCONFIRMED");
            }
            for (OrderRef order : fault.stream().filter(order -> !order.lost()).toList()) {
                assertThat(TestPgControl.complete(order.providerOrderId())).isEqualTo(1);
                confirmByReconcile(order);
            }
            assertThat(countOrders(fault, "CONFIRMED")).isEqualTo(7);
            publishAndAwaitActivities(17);
            int firstRevision = runWeeklyRanking("fault");
            assertRanking(firstRevision, 17);
            assertPhase(fault, new PhaseExpectation(7, 0, 0, 3, 10, 20, 10, 3, 3D, 3D));
            Map<String, Object> hypotheses = expectedHypotheses();
            faultFiles.add("hypotheses.json");
            evidence.writeJson("hypotheses.json", hypotheses);
            List<DiagnosisObservation> observations = observeDiagnosis(lost, logs);
            faultFiles.add("diagnosis.json");
            evidence.writeJson("diagnosis.json", diagnosisEvidence(observations));
            assertDiagnosis(observations);
            assertThat(unknownOrderIds(fault)).containsExactlyInAnyOrderElementsOf(lost.stream().map(OrderRef::orderId).toList());
            Map<String, Object> wallets = wallets(userA, userB);
            Map<String, LocalDateTime> confirmedBeforeRecovery = confirmedAt(baseline, fault);
            writePhase("fault", fault, List.of(Map.of("wallets", wallets, "ranking", Map.of("revision", firstRevision))));

            phase = "recovery";
            clock.set(T.plusSeconds(7_200));
            releaseInjections();
            assertNoActiveInjection();
            assertThat(unknownOrderIds(fault)).containsExactlyInAnyOrderElementsOf(lost.stream().map(OrderRef::orderId).toList());
            assertRecoveryPreconditions(lost);
            OrderRef probe = lost.getFirst();
            int lookupsBefore = TestPgControl.stats(probe.providerOrderId()).lookupRequests();
            assertThat(orderDetailStatus(userC, probe.orderId())).isEqualTo(404);
            assertThat(reconcile(userC, probe.orderId()).statusCode()).isEqualTo(404);
            assertThat(TestPgControl.stats(probe.providerOrderId()).lookupRequests()).isEqualTo(lookupsBefore);
            assertThat(attempt(probe.orderId()).get("status")).isEqualTo("UNKNOWN");
            assertThat(attempt(probe.orderId()).get("order_status")).isEqualTo("PAYMENT_PENDING");
            int createsBeforeRecovery = pgTotals().createRequests();
            for (OrderRef order : lost) {
                HttpResponse<String> reconciled = reconcile(order.userId(), order.orderId());
                assertThat(reconciled.statusCode()).isEqualTo(200);
                JsonNode payment = JSON.readTree(reconciled.body()).path("data");
                assertThat(payment.path("attemptStatus").asText()).isEqualTo("SUCCEEDED");
                assertThat(payment.path("orderStatus").asText()).isEqualTo("CONFIRMED");
                assertThat(orderDetail(order.userId(), order.orderId()).path("data").path("payment")
                    .path("paymentProgress").asText()).isEqualTo("COMPLETED");
            }
            assertThat(pgTotals().createRequests()).isEqualTo(createsBeforeRecovery);
            for (OrderRef order : fault) {
                Map<String, Object> recovered = attempt(order.orderId());
                assertThat(recovered.get("status")).isEqualTo("SUCCEEDED");
                assertThat(recovered.get("order_status")).isEqualTo("CONFIRMED");
                assertThat(((Number) recovered.get("payment_amount")).longValue())
                    .isEqualTo(((Number) recovered.get("amount")).longValue());
                assertThat(recovered.get("transaction_key")).isEqualTo(
                    pgTransactions(order.providerOrderId()).get(0).path("transactionKey").asText());
            }
            assertThat(wallets(userA, userB)).isEqualTo(wallets);
            assertConfirmedAt(baseline, fault, lost, confirmedBeforeRecovery);
            publishAndAwaitActivities(20);
            for (OrderRef order : fault) {
                assertThat(jdbc.queryForList("SELECT status FROM order_confirmed_outbox WHERE order_id = ?", String.class,
                    order.orderId())).containsExactly("PUBLISHED");
                assertThat(jdbc.queryForList("SELECT quantity FROM product_purchase_activity WHERE order_id = ?", Integer.class,
                    order.orderId())).containsExactly(1);
                assertThat(jdbc.queryForObject("SELECT confirmed_at FROM product_purchase_activity WHERE order_id = ?",
                    LocalDateTime.class, order.orderId())).isEqualTo(attempt(order.orderId()).get("confirmed_at"));
            }
            int secondRevision = runWeeklyRanking("recovery");
            assertThat(secondRevision).isEqualTo(firstRevision + 1);
            assertRanking(secondRevision, 20);
            assertThat(score(firstRevision)).isEqualTo(17L);
            assertPhase(fault, new PhaseExpectation(10, 0, 0, 0, 10, 20, 10, 0, 0D, 0D));
            writePhase("recovery", fault, List.of(Map.of("wallets", wallets(userA, userB),
                "ranking", Map.of("previousRevision", firstRevision, "revision", secondRevision))));
            writeOrders(baseline, fault);

            phase = "convergence";
            clock.set(T.plusSeconds(10_800));
            PgTotals totalsBefore = pgTotals();
            // 장애 3건은 PG가 callback을 보내지 않았다. 복구 뒤에 늦은 성공 알림이 오는 경우를 테스트가 직접 보낸다.
            List<Map<String, Object>> lateCallbacks = new ArrayList<>();
            for (OrderRef order : lost) {
                Map<String, Object> current = attempt(order.orderId());
                HttpResponse<String> late = api("POST", "/api/v1/payments/callback", null, JSON.writeValueAsString(Map.of(
                    "providerOrderId", order.providerOrderId(), "transactionKey", current.get("transaction_key"),
                    "status", "SUCCESS")));
                assertThat(late.statusCode()).isEqualTo(200);
                lateCallbacks.add(Map.of("orderId", order.orderId(), "httpStatus", late.statusCode()));
            }
            evidence.writeJson("raw/convergence-late-callbacks.json", lateCallbacks);
            for (OrderRef order : fault) {
                assertThat(reconcile(order.userId(), order.orderId()).statusCode()).isEqualTo(200);
            }
            PgTotals totalsAfter = pgTotals();
            assertThat(totalsAfter.createRequests()).isEqualTo(totalsBefore.createRequests());
            assertThat(totalsAfter.lookupRequests()).isEqualTo(totalsBefore.lookupRequests());
            assertThat(countOrders(fault, "CONFIRMED")).isEqualTo(10);
            assertConfirmedAt(baseline, fault, lost, confirmedBeforeRecovery);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_confirmed_outbox", Long.class)).isEqualTo(20L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class)).isEqualTo(20L);
            assertThat(stock()).isEqualTo(10L);
            assertThat(reservedQuantity()).isZero();

            phase = "verification";
            Map<String, String> recordedHashes = evidence.recordedHashes();
            for (String file : faultFiles) {
                assertThat(evidence.sha256(file)).as("fault 증거가 바뀌지 않았다: %s", file).isEqualTo(recordedHashes.get(file));
            }
            manifest.put("faultFileHashes", faultFiles.stream()
                .collect(Collectors.toMap(file -> file, recordedHashes::get, (a, b) -> a, LinkedHashMap::new)));
            writeLogs(logs);
            logsWritten = true;
            if (streamer != null) {
                streamer.close();
                streamer = null;
                evidence.recordHash("logs/commerce-streamer.log");
            }
            assertThat(evidence.filesContaining(CARD_NO)).as("증거에 카드 번호가 없다").isEmpty();
            manifest.put("result", "PASSED");
        } catch (Throwable failure) {
            primaryFailure = failure;
            manifest.put("result", "FAILED");
            manifest.put("failedPhase", phase);
            manifest.put("failure", failure.toString());
            throw failure;
        } finally {
            try {
                cleanUp(streamer, logs, logsWritten);
            } catch (RuntimeException cleanupFailure) {
                if (primaryFailure == null) {
                    throw cleanupFailure;
                }
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    // 실패해도 이 실행이 켠 주입만 기본 시나리오로 되돌린다. 거래와 호출 기록은 지우지 않는다.
    // 한 단계가 실패해도 나머지 정리와 manifest 기록은 계속하고, 실패는 manifest에 남긴다.
    private void cleanUp(ChildApp streamer, CapturedLogs logs, boolean logsWritten) {
        Map<String, Object> cleanup = new LinkedHashMap<>();
        cleanup.put("injectionsLeftAtEnd", activeInjections.size());
        cleanupStep(cleanup, "testPgCleanupFailure", this::releaseInjections);
        cleanupStep(cleanup, "couponAdmissionFailure", couponAdmission::close);
        if (!logsWritten) {
            cleanupStep(cleanup, "logWriteFailure", () -> writeLogs(logs));
        }
        logs.close();
        if (streamer != null) {
            cleanupStep(cleanup, "streamerStopFailure", () -> {
                streamer.close();
                evidence.recordHash("logs/commerce-streamer.log");
            });
        }
        manifest.put("cleanup", cleanup);
        manifest.put("finishedAt", Instant.now().toString());
        evidence.writeManifest(manifest);
    }

    private static void cleanupStep(Map<String, Object> cleanup, String failureKey, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException exception) {
            cleanup.put(failureKey, exception.toString());
        }
    }

    private void writeLogs(CapturedLogs logs) {
        evidence.writeText("logs/commerce-api.jsonl", logs.toJsonLines());
        evidence.writeText("logs/test-pg.log", TestPgContainer.logs());
    }

    // PG 거래를 PG 조회 API로 직접 읽는다. Commerce를 거치지 않아 Commerce 상태를 바꾸지 않는다.
    private static JsonNode pgTransactions(String providerOrderId) {
        try {
            return JSON.readTree(TestPgControl.lookupBodyByOrder(providerOrderId));
        } catch (IOException exception) {
            throw new IllegalStateException("테스트 PG 거래 조회 응답을 읽지 못했습니다: " + providerOrderId, exception);
        }
    }

    private void createStreamerTables() throws IOException {
        String initSql = Files.readString(Path.of(System.getProperty("incident.init-sql")), StandardCharsets.UTF_8);
        for (String statement : initSql.split(";")) {
            String trimmed = statement.replaceAll("(?m)^--.*$", "").trim();
            if (trimmed.startsWith("CREATE TABLE IF NOT EXISTS processed_order_confirmation")
                || trimmed.startsWith("CREATE TABLE IF NOT EXISTS product_purchase_activity")) {
                jdbc.execute(trimmed);
            }
        }
    }

    // API 시작 작업이 이 실행 전용 topic을 먼저 만들 수 있어, 없을 때만 만들고 partition 수를 확인한다.
    private void createTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", infra.kafkaBootstrap()))) {
            try {
                admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof TopicExistsException)) {
                    throw failure;
                }
            }
            assertThat(admin.describeTopics(List.of(TOPIC)).allTopicNames().get(30, TimeUnit.SECONDS)
                .get(TOPIC).partitions()).hasSize(3);
        }
    }

    private void awaitConsumerAssigned(ChildApp streamer) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", infra.kafkaBootstrap()))) {
            await("streamer consumer가 partition 3개를 배정받음", () -> {
                assertThat(streamer.isAlive()).as("streamer 프로세스가 살아 있다").isTrue();
                try {
                    ConsumerGroupDescription group = admin.describeConsumerGroups(List.of(GROUP)).all()
                        .get(10, TimeUnit.SECONDS).get(GROUP);
                    int partitions = group.members().stream().mapToInt(member -> member.assignment().topicPartitions().size()).sum();
                    return group.state() == ConsumerGroupState.STABLE && partitions == 3;
                } catch (Exception exception) {
                    return false;
                }
            }, Duration.ofSeconds(120));
        }
    }

    // 관리자 API는 로그인이 필요해 MockMvc의 테스트 관리자 사용자로 부른다.
    private long createProduct() throws Exception {
        long brandId = adminCreate("/api-admin/v1/brands", "{\"name\":\"incident-brand-" + evidence.runId() + "\"}");
        long createdProductId = adminCreate("/api-admin/v1/products",
            "{\"brandId\":" + brandId + ",\"name\":\"incident-product\",\"price\":" + PRICE + "}");
        MvcResult stock = mvc.perform(request(HttpMethod.PUT, "/api-admin/v1/products/" + createdProductId + "/stock")
            .with(user("admin").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + INITIAL_STOCK + "}")).andReturn();
        assertThat(stock.getResponse().getStatus()).isEqualTo(200);
        return createdProductId;
    }

    private long adminCreate(String path, String body) throws Exception {
        MvcResult created = mvc.perform(request(HttpMethod.POST, path).with(user("admin").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(created.getResponse().getStatus()).as(path).isEqualTo(201);
        return JSON.readTree(created.getResponse().getContentAsString()).at("/data/id").asLong();
    }

    private void prepareWallets(long userA, long userB) throws Exception {
        long campaignId = couponSeedService.prepare(false).getId();
        couponAdmission.open();
        for (long userId : List.of(userA, userB)) {
            pointRepository.save(Point.create(userId));
            assertThat(api("POST", "/api/v1/points/charge", userId, "{\"amount\":" + POINTS + "}").statusCode()).isEqualTo(200);
            assertThat(api("POST", "/api/v1/coupon-campaigns/" + campaignId + "/issues", userId, null).statusCode()).isEqualTo(201);
        }
    }

    private Map<String, Object> wallets(long userA, long userB) throws Exception {
        Map<String, Object> wallets = new LinkedHashMap<>();
        for (long userId : List.of(userA, userB)) {
            JsonNode points = JSON.readTree(api("GET", "/api/v1/points", userId, null).body()).path("data");
            JsonNode coupons = JSON.readTree(api("GET", "/api/v1/coupons", userId, null).body()).path("data");
            List<Long> couponIds = new ArrayList<>();
            coupons.forEach(coupon -> couponIds.add(coupon.path("couponId").asLong()));
            wallets.put(String.valueOf(userId), Map.of("points", points.path("balance").asLong(), "couponIds", couponIds));
        }
        return wallets;
    }

    private OrderRef createOrder(String cohort, long userId, boolean lost) throws Exception {
        HttpResponse<String> created = api("POST", "/api/v1/orders", userId,
            "{\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}");
        assertThat(created.statusCode()).isEqualTo(201);
        long orderId = JSON.readTree(created.body()).at("/data/id").asLong();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        OrderRef order = new OrderRef(cohort, userId, orderId, UUID.randomUUID().toString(),
            "LP-ORD-%010d-%s".formatted(orderId, suffix), suffix, lost);
        createdOrders.add(order);
        return order;
    }

    // PG 주문 식별자의 suffix는 실행마다 무작위로 만든 값을 결제 요청 직전에 정한다. 증거에는 DB에서 다시 읽은 값을 쓴다.
    private JsonNode confirm(OrderRef order) throws Exception {
        nextSuffix.set(order.suffix());
        HttpResponse<String> confirmed = api("POST", "/api/v1/orders/" + order.orderId() + "/confirm", order.userId(),
            "{\"paymentAttemptKey\":\"" + order.paymentAttemptKey() + "\",\"cardType\":\"TEST\",\"cardNo\":\"" + CARD_NO + "\"}");
        assertThat(confirmed.statusCode()).as("결제 요청 %s", confirmed.body()).isEqualTo(202);
        assertThat(attempt(order.orderId()).get("provider_order_id")).isEqualTo(order.providerOrderId());
        return JSON.readTree(confirmed.body()).path("data");
    }

    private JsonNode orderDetail(long userId, long orderId) throws Exception {
        HttpResponse<String> detail = api("GET", "/api/v1/orders/" + orderId, userId, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        return JSON.readTree(detail.body());
    }

    private int orderDetailStatus(long userId, long orderId) throws Exception {
        return api("GET", "/api/v1/orders/" + orderId, userId, null).statusCode();
    }

    private HttpResponse<String> reconcile(long userId, long orderId) throws Exception {
        return api("POST", "/api/v1/orders/" + orderId + "/payment/reconcile", userId, null);
    }

    private void publishAndAwaitActivities(long expected) {
        await("Outbox 발행", () -> {
            relay.runDueBatch();
            return jdbc.queryForObject("SELECT COUNT(*) FROM order_confirmed_outbox WHERE status <> 'PUBLISHED'", Long.class) == 0;
        });
        await("구매 활동 " + expected + "건", () ->
            jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class) == expected);
    }

    private int runWeeklyRanking(String label) {
        int revision = jdbc.queryForObject(
            "SELECT COALESCE(MAX(revision), 0) + 1 FROM weekly_ranking_revisions WHERE period_start = ?", Integer.class, PERIOD_START);
        String log = "logs/commerce-batch-" + label + ".log";
        int exitCode = ChildApp.runToCompletion("commerce-batch", Path.of(System.getProperty("incident.batch-jar")),
            ChildApp.weeklyRankingArguments(infra, PERIOD_START, revision), evidence.path(log), Duration.ofMinutes(3));
        evidence.recordHash(log);
        assertThat(exitCode).as("주간 랭킹 Job 종료 코드 (%s)", log).isZero();
        return revision;
    }

    private void assertRanking(int revision, long score) throws Exception {
        JsonNode ranking = JSON.readTree(api("GET", "/api/v1/rankings/weekly?periodStart=" + PERIOD_START, null, null).body())
            .path("data");
        assertThat(ranking.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(ranking.path("revision").asInt()).isEqualTo(revision);
        assertThat(ranking.path("items")).hasSize(1);
        assertThat(ranking.at("/items/0/productId").asLong()).isEqualTo(productId);
        assertThat(ranking.at("/items/0/rank").asInt()).isEqualTo(1);
        assertThat(ranking.at("/items/0/score").asLong()).isEqualTo(score);
    }

    private Long score(int revision) {
        return jdbc.queryForObject("SELECT score FROM weekly_ranking_scores WHERE period_start = ? AND revision = ? AND product_id = ?",
            Long.class, PERIOD_START, revision, productId);
    }

    private void assertPhase(List<OrderRef> orders, PhaseExpectation expected) throws Exception {
        assertThat(paymentOutcomes(orders)).as("대상 %d건의 결제 결과", orders.size())
            .containsExactlyEntriesOf(expected.outcomes());
        int successTransactions = 0;
        for (OrderRef order : orders) {
            JsonNode transactions = pgTransactions(order.providerOrderId());
            assertThat(transactions).as("PG 거래 1건: %s", order.providerOrderId()).hasSize(1);
            assertThat(transactions.get(0).path("amount").asLong()).isEqualTo(PRICE);
            assertThat(TestPgControl.stats(order.providerOrderId()).createRequests()).isEqualTo(1);
            successTransactions += "SUCCESS".equals(transactions.get(0).path("status").asText()) ? 1 : 0;
        }
        assertThat(successTransactions).isEqualTo(expected.pgSuccess());
        PgTotals totals = pgTotals();
        assertThat(totals.createRequests()).isEqualTo(expected.cumulative());
        assertThat(totals.transactions()).isEqualTo(expected.cumulative());
        assertThat(stock()).isEqualTo(expected.stock());
        assertThat(reservedQuantity()).isEqualTo(expected.reserved());
        await("지표 UNKNOWN " + expected.unknownMetric() + ", REQUESTED·PENDING 0, 기한 초과 " + expected.overdueMetric(), () -> {
            String body = prometheus();
            return unresolvedMetric(body, "UNKNOWN").orElse(-1) == expected.unknownMetric()
                && unresolvedMetric(body, "REQUESTED").orElse(-1) == 0
                && unresolvedMetric(body, "PENDING").orElse(-1) == 0
                && overdueMetric(body).orElse(-1) == expected.overdueMetric();
        }, Duration.ofSeconds(10));
        assertThat(JSON.readTree(management("/actuator/health")).path("status").asText()).isEqualTo("UP");
    }

    private void writePhase(String phase, List<OrderRef> orders, List<Map<String, Object>> extras) throws Exception {
        String healthFile = "raw/" + phase + "-health.json";
        String readinessFile = "raw/" + phase + "-health-readiness.json";
        String prometheusFile = "raw/" + phase + "-prometheus.txt";
        String prometheus = prometheus();
        record(phase, healthFile, management("/actuator/health"));
        record(phase, readinessFile, management("/actuator/health/readiness"));
        record(phase, prometheusFile, prometheus);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("phase", phase);
        snapshot.put("observedAt", Instant.now().toString());
        snapshot.put("businessClock", clock.instant().toString());
        Map<String, Object> outcomes = new LinkedHashMap<>();
        outcomes.put("denominator", orders.size());
        outcomes.putAll(paymentOutcomes(orders));
        snapshot.put("paymentOutcomes", outcomes);
        snapshot.put("orders", orderSnapshots(orders));
        snapshot.put("pgTotals", pgTotals());
        snapshot.put("stock", stock());
        snapshot.put("reservedForPaymentPending", reservedQuantity());
        snapshot.put("purchaseActivities", jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class));
        snapshot.put("outbox", jdbc.queryForList("SELECT order_id, event_id, status, attempt_count FROM order_confirmed_outbox ORDER BY order_id"));
        snapshot.put("rankingRevisions", jdbc.queryForList(
            "SELECT period_start, revision, status, calculated_at, result_checksum FROM weekly_ranking_revisions ORDER BY revision"));
        Map<String, Object> metric = new LinkedHashMap<>();
        for (String status : List.of("REQUESTED", "PENDING", "UNKNOWN")) {
            metric.put(status, unresolvedMetric(prometheus, status).orElse(-1));
        }
        snapshot.put("metric", Map.of("source", prometheusFile, "payment_attempt_unresolved", metric,
            "payment_attempt_overdue_unknown", overdueMetric(prometheus).orElse(-1)));
        snapshot.put("health", Map.of("root", healthFile, "readiness", readinessFile));
        extras.forEach(snapshot::putAll);
        String file = "phase-" + phase + ".json";
        record(phase, file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
    }

    private void record(String phase, String file, String text) {
        if ("fault".equals(phase)) {
            faultFiles.add(file);
        }
        evidence.writeText(file, text);
    }

    private List<Map<String, Object>> orderSnapshots(List<OrderRef> orders) {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (OrderRef order : orders) {
            Map<String, Object> row = new LinkedHashMap<>(attempt(order.orderId()));
            row.put("cohort", order.cohort());
            row.put("lostResponseInjected", order.lost());
            row.put("pgTransactions", pgTransactions(order.providerOrderId()));
            row.put("pgStats", TestPgControl.stats(order.providerOrderId()));
            snapshots.add(row);
        }
        return snapshots;
    }

    private Map<String, Object> expectedHypotheses() {
        return Map.of(
            "writtenAt", Instant.now().toString(),
            "hypotheses", List.of(
                Map.of("name", "PG_PROCESSING_DELAY", "expected", "PG 거래가 아직 PENDING", "check", "PG 거래 조회"),
                Map.of("name", "PG_RESPONSE_LOST", "expected",
                    "PG 거래 SUCCESS 1건, 생성 관찰 UNKNOWN, elapsedMs가 응답 timeout 이상", "check",
                    "PG 거래 조회, payment.create.observed"),
                Map.of("name", "INTERNAL_APPLY_FAILURE", "expected", "조회 관찰 FOUND 뒤 결과 반영 rollback error", "check",
                    "payment.lookup.observed, 결과 반영 rollback error, 시도 상태")));
    }

    // 시도마다 진단 조회 앞뒤의 Commerce 상태와 판정에 쓰는 관찰을 모은다.
    private List<DiagnosisObservation> observeDiagnosis(List<OrderRef> lost, CapturedLogs logs) {
        List<DiagnosisObservation> observations = new ArrayList<>();
        for (OrderRef order : lost) {
            Map<String, Object> beforeRow = attempt(order.orderId());
            JsonNode transactions = pgTransactions(order.providerOrderId());
            Map<String, Object> after = commerceState(attempt(order.orderId()));
            Map<String, Object> before = commerceState(beforeRow);
            String attemptId = String.valueOf(beforeRow.get("attempt_id"));
            // rollback은 결과 반영 TX의 기존 error 로그 문장으로 찾는다.
            List<CapturedLogs.Entry> rolledBack = logs.entries().stream()
                .filter(entry -> "ERROR".equals(entry.level()) && entry.message().contains("결제 결과 반영을 되돌립니다.")
                    && entry.message().contains("attemptId=" + attemptId + ","))
                .toList();
            observations.add(new DiagnosisObservation(order, attemptId, before, after,
                logs.events("payment.create.observed", "attemptId", attemptId), transactions,
                logs.events("payment.lookup.observed", "attemptId", attemptId), rolledBack));
        }
        return observations;
    }

    private static Map<String, Object> commerceState(Map<String, Object> attempt) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", attempt.get("status"));
        state.put("orderStatus", attempt.get("order_status"));
        state.put("updatedAt", attempt.get("updated_at"));
        return state;
    }

    // 관찰은 확인보다 먼저 증거로 쓴다. 확인이 실패해도 무엇을 봤는지 남는다. 판정은 이 관찰로 사람이 내린다.
    private Map<String, Object> diagnosisEvidence(List<DiagnosisObservation> observations) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (DiagnosisObservation observed : observations) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderId", observed.order().orderId());
            result.put("attemptId", observed.attemptId());
            result.put("providerOrderId", observed.order().providerOrderId());
            result.put("createObserved", observed.created().stream().map(CapturedLogs.Entry::values).toList());
            result.put("responseTimeoutMs", responseTimeout.toMillis());
            result.put("pgTransactions", observed.transactions());
            result.put("lookupObservations", observed.lookups().size());
            result.put("rollbackLogs", observed.rolledBack().size());
            result.put("commerceBeforeLookup", observed.before());
            result.put("commerceAfterLookup", observed.after());
            result.put("evidence", observed.created().stream().map(CapturedLogs.Entry::citation).toList());
            results.add(result);
        }
        return Map.of("observedAt", Instant.now().toString(), "attempts", results);
    }

    private void assertDiagnosis(List<DiagnosisObservation> observations) {
        for (DiagnosisObservation observed : observations) {
            String attemptId = observed.attemptId();
            assertThat(observed.after()).as("진단 조회 앞뒤 Commerce 상태 %s", attemptId).isEqualTo(observed.before());
            assertThat(observed.created()).as("생성 관찰 %s", attemptId).singleElement().satisfies(entry -> {
                assertThat(entry.level()).isEqualTo("WARN");
                assertThat(entry.values().get("outcome")).isEqualTo("UNKNOWN");
                assertThat(Long.parseLong(entry.values().get("elapsedMs"))).isGreaterThanOrEqualTo(responseTimeout.toMillis());
            });
            assertThat(observed.transactions()).as("PG 거래 %s", observed.order().providerOrderId()).singleElement()
                .satisfies(transaction -> {
                    assertThat(transaction.path("status").asText()).isEqualTo("SUCCESS");
                    assertThat(transaction.path("amount").asLong()).isEqualTo(PRICE);
                    assertThat(transaction.path("orderId").asText()).isEqualTo(observed.order().providerOrderId());
                });
            assertThat(observed.lookups()).as("조회 관찰 %s", attemptId).isEmpty();
            assertThat(observed.rolledBack()).as("rollback 기록 %s", attemptId).isEmpty();
            assertThat(observed.after().get("status")).isEqualTo("UNKNOWN");
        }
    }

    private void assertRecoveryPreconditions(List<OrderRef> lost) {
        for (OrderRef order : lost) {
            JsonNode transactions = pgTransactions(order.providerOrderId());
            assertThat(transactions).hasSize(1);
            assertThat(transactions.get(0).path("status").asText()).isEqualTo("SUCCESS");
            assertThat(transactions.get(0).path("amount").asLong()).isEqualTo(PRICE);
            assertThat(transactions.get(0).path("orderId").asText()).isEqualTo(order.providerOrderId());
            Map<String, Object> current = attempt(order.orderId());
            assertThat(current.get("conflict_reason")).isNull();
            assertThat(current.get("status")).isEqualTo("UNKNOWN");
        }
    }

    private Map<String, LocalDateTime> confirmedAt(List<OrderRef> baseline, List<OrderRef> fault) {
        Map<String, LocalDateTime> confirmed = new LinkedHashMap<>();
        for (OrderRef order : concat(baseline, fault)) {
            if (!order.lost()) {
                confirmed.put(String.valueOf(order.orderId()), (LocalDateTime) attempt(order.orderId()).get("confirmed_at"));
            }
        }
        return confirmed;
    }

    private void assertConfirmedAt(List<OrderRef> baseline, List<OrderRef> fault, List<OrderRef> lost,
                                   Map<String, LocalDateTime> confirmedBeforeRecovery) {
        LocalDateTime periodStart = PERIOD_START.atStartOfDay();
        LocalDateTime recoveredAt = LocalDateTime.ofInstant(T.plusSeconds(7_200), ZoneOffset.UTC);
        for (OrderRef order : concat(baseline, fault)) {
            LocalDateTime confirmedAt = (LocalDateTime) attempt(order.orderId()).get("confirmed_at");
            assertThat(confirmedAt).isNotNull().isAfterOrEqualTo(periodStart).isBefore(periodStart.plusDays(7));
            if (lost.contains(order)) {
                assertThat(confirmedAt).isEqualTo(recoveredAt);
            } else {
                assertThat(confirmedAt).isEqualTo(confirmedBeforeRecovery.get(String.valueOf(order.orderId())));
            }
        }
    }

    // 이 실행이 등록한 응답 유실 주입이 모두 해제됐고 테스트 PG의 조회 지연·실패 설정이 없으면 주입이 꺼진 상태다.
    private void assertNoActiveInjection() {
        assertThat(activeInjections).as("응답 유실 주입이 꺼져 있다").isEmpty();
        JsonNode lookup = TestPgControl.lookupSetting();
        assertThat(lookup.required("delayMillis").asLong()).as("조회 지연 주입이 꺼져 있다").isZero();
        assertThat(lookup.required("failuresRemaining").asInt()).as("조회 실패 주입이 꺼져 있다").isZero();
    }

    // 같은 주문에 기본 시나리오 SUCCESS를 다시 등록해 주입을 해제한다. 거래와 호출 기록은 그대로 남는다.
    private void releaseInjections() {
        for (String providerOrderId : List.copyOf(activeInjections.keySet())) {
            TestPgControl.scenario(providerOrderId, "{\"scenario\":\"SUCCESS\"}");
            activeInjections.remove(providerOrderId);
        }
    }

    private Map<String, Object> injectionState() {
        return Map.of("injections", new LinkedHashMap<>(activeInjections), "lookup", TestPgControl.lookupSetting());
    }

    private void confirmByReconcile(OrderRef order) throws Exception {
        HttpResponse<String> reconciled = reconcile(order.userId(), order.orderId());
        assertThat(reconciled.statusCode()).as("정상 주문 reconcile %s", reconciled.body()).isEqualTo(200);
        assertThat(JSON.readTree(reconciled.body()).at("/data/orderStatus").asText()).isEqualTo("CONFIRMED");
    }

    // incidentTest JVM에서는 이 실험만 테스트 PG를 쓰므로, 주문별 집계를 더하면 PG 전체 집계다. 이 집계 조회는 조회 수에 들어가지 않는다.
    private PgTotals pgTotals() {
        int creates = 0;
        int lookups = 0;
        int callbacks = 0;
        int transactions = 0;
        for (OrderRef order : createdOrders) {
            TestPgControl.Stats stats = TestPgControl.stats(order.providerOrderId());
            creates += stats.createRequests();
            lookups += stats.lookupRequests();
            callbacks += stats.callbackAttempts();
            transactions += stats.transactions();
        }
        return new PgTotals(creates, lookups, callbacks, transactions);
    }

    private Map<String, Object> attempt(long orderId) {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT a.id AS attempt_id, a.order_id, a.payment_attempt_key, a.provider_order_id, a.transaction_key, a.amount,
                   a.status, a.conflict_reason, a.created_at, a.updated_at, a.last_checked_at,
                   o.status AS order_status, o.payment_amount, o.confirmed_at
            FROM payment_attempts a JOIN orders o ON o.id = a.order_id
            WHERE a.order_id = ?
            """, orderId);
        Map<String, Object> converted = new LinkedHashMap<>();
        row.forEach((key, value) -> converted.put(key.toLowerCase(), value instanceof Timestamp timestamp
            ? timestamp.toLocalDateTime() : value));
        return converted;
    }

    private List<Long> unknownOrderIds(List<OrderRef> orders) {
        return orders.stream().filter(order -> "UNKNOWN".equals(attempt(order.orderId()).get("status")))
            .map(OrderRef::orderId).toList();
    }

    private Map<String, Integer> paymentOutcomes(List<OrderRef> orders) {
        Map<String, Integer> outcomes = PhaseExpectation.emptyOutcomes();
        for (OrderRef order : orders) {
            String status = String.valueOf(attempt(order.orderId()).get("status"));
            String outcome = switch (status) {
                case "SUCCEEDED" -> "succeeded";
                case "FAILED" -> "failed";
                case "REQUESTED", "PENDING" -> "processing";
                case "UNKNOWN" -> "unknown";
                default -> throw new AssertionError("분류할 수 없는 결제 시도 상태: " + status);
            };
            outcomes.merge(outcome, 1, Integer::sum);
        }
        return outcomes;
    }

    private int countOrders(List<OrderRef> orders, String status) {
        return (int) orders.stream().filter(order -> status.equals(attempt(order.orderId()).get("order_status"))).count();
    }

    private long stock() {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Long.class, productId);
    }

    // 결제 확인 중(PAYMENT_PENDING) 주문이 차감해 둔 수량이다. 확정되면 빠지고, 실패하면 재고로 돌아간다.
    private long reservedQuantity() {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(i.quantity), 0) FROM order_items i JOIN orders o ON o.id = i.order_id
            WHERE o.status = 'PAYMENT_PENDING' AND i.product_id = ?
            """, Long.class, productId);
    }

    // 두 집합의 식별자를 남긴다. 장애 3건의 거래 키는 복구 뒤에 채워지므로 recovery 다음에 쓴다.
    private void writeOrders(List<OrderRef> baseline, List<OrderRef> fault) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrderRef order : concat(baseline, fault)) {
            Map<String, Object> current = attempt(order.orderId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cohort", order.cohort());
            row.put("orderId", order.orderId());
            row.put("paymentAttemptKey", current.get("payment_attempt_key"));
            row.put("providerOrderId", current.get("provider_order_id"));
            row.put("transactionKey", current.get("transaction_key"));
            row.put("amount", current.get("amount"));
            row.put("lostResponseInjected", order.lost());
            rows.add(row);
        }
        evidence.writeJson("orders.json", rows);
    }

    private static List<OrderRef> concat(List<OrderRef> first, List<OrderRef> second) {
        List<OrderRef> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private HttpResponse<String> api(String method, String path, Long userId, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15));
        if (userId != null) {
            builder.header("X-USER-ID", String.valueOf(userId));
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // 실제 /actuator/prometheus 응답 원문에서 읽는다. 값을 따로 만들지 않는다.
    private static OptionalDouble unresolvedMetric(String body, String status) {
        String prefix = "payment_attempt_unresolved{";
        for (String line : body.split("\n")) {
            if (line.startsWith(prefix) && line.contains("status=\"" + status + "\"")) {
                return OptionalDouble.of(Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)));
            }
        }
        return OptionalDouble.empty();
    }

    private static OptionalDouble overdueMetric(String body) {
        for (String line : body.split("\n")) {
            if (line.startsWith("payment_attempt_overdue_unknown{") || line.startsWith("payment_attempt_overdue_unknown ")) {
                return OptionalDouble.of(Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)));
            }
        }
        return OptionalDouble.empty();
    }

    private String prometheus() throws Exception {
        return management("/actuator/prometheus");
    }

    private int managementStatus(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String management(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + managementPort + path)).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return response.body();
    }

    private static void await(String description, ThrowingBooleanSupplier condition) {
        await(description, condition, WAIT);
    }

    // 기다리는 단계는 모두 상한을 둔다. 넘으면 그 단계를 실패로 남긴다.
    private static void await(String description, ThrowingBooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (AssertionError error) {
                throw error;
            } catch (Exception exception) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError(description + " 대기 중 오류", exception);
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(description + "을(를) " + timeout + " 안에 확인하지 못했습니다.");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(description + " 대기가 중단됐습니다.", interrupted);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private record PhaseExpectation(int succeeded, int failed, int processing, int unknown, int pgSuccess, int cumulative,
                                    long stock, long reserved, double unknownMetric, double overdueMetric) {
        static Map<String, Integer> emptyOutcomes() {
            Map<String, Integer> outcomes = new LinkedHashMap<>();
            outcomes.put("succeeded", 0);
            outcomes.put("failed", 0);
            outcomes.put("processing", 0);
            outcomes.put("unknown", 0);
            return outcomes;
        }

        Map<String, Integer> outcomes() {
            Map<String, Integer> outcomes = emptyOutcomes();
            outcomes.put("succeeded", succeeded);
            outcomes.put("failed", failed);
            outcomes.put("processing", processing);
            outcomes.put("unknown", unknown);
            return outcomes;
        }
    }

    private record PgTotals(int createRequests, int lookupRequests, int callbackAttempts, int transactions) {}

    private record DiagnosisObservation(OrderRef order, String attemptId, Map<String, Object> before,
                                        Map<String, Object> after, List<CapturedLogs.Entry> created, JsonNode transactions,
                                        List<CapturedLogs.Entry> lookups, List<CapturedLogs.Entry> rolledBack) {
    }

    private record OrderRef(String cohort, long userId, long orderId, String paymentAttemptKey, String providerOrderId,
                            String suffix, boolean lost) {}
}
