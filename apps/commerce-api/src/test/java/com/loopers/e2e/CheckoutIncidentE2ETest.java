package com.loopers.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.PaymentFlowTestSupport;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.support.batch.BatchProcess;
import com.loopers.support.fixture.CouponCampaigns;
import com.loopers.support.incident.IncidentEvidence;
import com.loopers.support.kafka.TopicReader;
import com.loopers.support.log.LogCapture;
import com.loopers.support.log.LogCapture.Captured;
import com.loopers.support.pg.FakePgServer.RecordedRequest;
import com.loopers.support.streamer.StreamerProcess;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PG 는 SUCCESS 인데 생성 응답을 잃어 내부가 UNKNOWN 으로 남는 사고를 한 lifecycle 에서 재현하고 복구한다.
 * baseline → fault → 진단·보존 → recovery → 후속(Outbox·Kafka·구매 활동·주간 랭킹) 순서로 같은 DB·같은 PG 거래 상태를 잇는다.
 * commerce DB 의 상태를 직접 쓰지 않고 실제 API 로만 바꾼다. 증거는 {@code build/incident-runs/{runId}/} 에 남는다.
 */
@Tag("e2e")
@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "management.server.port=0",
        "outbox.relay.enabled=true",
        "outbox.relay.fixed-delay=200ms",
        "payment.metrics.unresolved-refresh-interval=200ms"
    }
)
@Import(CheckoutIncidentE2ETest.FixedClockConfig.class)
class CheckoutIncidentE2ETest extends PaymentFlowTestSupport {

    // 모든 주문이 같은 주(2026-11-23 월요일 시작)에 처음 확정되도록 시계를 수요일에 고정한다.
    private static final Instant FIXED_NOW = Instant.parse("2026-11-25T03:00:00Z");
    private static final LocalDate PERIOD_START = LocalDate.parse("2026-11-23");
    private static final int ORDERS_PER_SET = 10;
    // 장애 대상은 실행 순서가 아니라 생성한 장애 집합 안의 고정 위치(2·5·9번째)로 정하고, 실제 ID 로 바꿔 fixture 에 넘긴다.
    private static final List<Integer> FAULT_POSITIONS = List.of(1, 4, 8);
    private static final int INITIAL_STOCK = 25;
    private static final long CHARGED_POINTS = 50_000;
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String TOPIC = "incident-e2e-" + UUID.randomUUID();
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void topicProperty(DynamicPropertyRegistry registry) {
        registry.add("order-event.topic", () -> TOPIC);
    }

    @Autowired
    private TestRestTemplate http;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private CouponCampaigns couponCampaigns;

    private IncidentEvidence evidence;
    private LogCapture logs;
    private Product product;
    private User baselineUser;
    private User faultUser;
    private List<Long> baselineOrders;
    private List<Long> faultOrders;
    private List<Long> lostOrders;
    private long faultUserCouponCount;
    private String faultFileHash;

    @DisplayName("PG 성공 응답을 잃은 3건을 같은 거래 조회로 복구하면, create 추가 없이 같은 10건이 확정되고 주간 랭킹까지 반영된다.")
    @Test
    void responseLostPayments_recoverWithoutNewCreate_andReachWeeklyRanking() throws Exception {
        evidence = IncidentEvidence.create();
        evidence.note("fixedClock", FIXED_NOW);
        evidence.note("periodStart", PERIOD_START);
        String consumerGroup = "purchase-activity-" + UUID.randomUUID();
        TopicReader.createTopic(KafkaTestContainersConfig.bootstrapServers(), TOPIC);
        try (StreamerProcess streamer = StreamerProcess.start(TOPIC, consumerGroup);
             TopicReader reader = new TopicReader(KafkaTestContainersConfig.bootstrapServers(), TOPIC);
             LogCapture capture = LogCapture.start()) {
            logs = capture;
            prepare();
            baseline();
            fault();
            diagnoseAndPreserve();
            recover();
            followUp(reader, streamer);
            evidence.note("result", "PASSED");
        } catch (Throwable e) {
            evidence.note("result", "FAILED");
            evidence.note("failure", e.toString());
            throw e;
        } finally {
            // 이 실행이 켠 주입만 해제한다. 거래·증거는 초기화하지 않는다.
            pg.clearDropResponse();
            couponCampaigns.clear();
            evidence.finish();
        }
    }

    // ---- 단계 ----

    private void prepare() throws Exception {
        Brand brand = brandRepository.save(Brand.create("사고실험"));
        product = productRepository.save(Product.create(brand.getId(), "사고실험 상품", new Money(1_000), new Stock(INITIAL_STOCK)));
        baselineUser = saveUser(0);
        faultUser = saveUser(0);

        // 장애 집합 사용자의 포인트·쿠폰은 PG 복구와 무관해야 한다. 실제 API 로 기준값을 만든다.
        assertThat(post("/api/v1/points/charge", faultUser, "{\"amount\":" + CHARGED_POINTS + "}").getStatusCode()).isEqualTo(HttpStatus.OK);
        Long campaignId = couponCampaigns.open(5);
        assertThat(post("/api/v1/coupon-campaigns/" + campaignId + "/issues", faultUser, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        faultUserCouponCount = couponCountOf(faultUser);
        assertThat(faultUserCouponCount).isEqualTo(1);
    }

    private void baseline() throws Exception {
        assertThat(pg.isDroppingResponses()).isFalse();
        baselineOrders = createOrders(baselineUser);
        for (Long orderId : baselineOrders) {
            assertThat(paymentStatusOf(confirm(baselineUser, orderId))).isEqualTo("PENDING");
            settleAndNotify(orderId);
        }

        Map<String, Long> attempts = attemptStatusCounts(baselineOrders);
        assertThat(attempts.getOrDefault("SUCCEEDED", 0L)).isEqualTo(ORDERS_PER_SET);
        assertThat(attempts.getOrDefault("UNKNOWN", 0L)).isZero();
        assertThat(pgSuccessCount(baselineOrders)).isEqualTo(ORDERS_PER_SET);
        assertThat(pg.createCalls()).isEqualTo(ORDERS_PER_SET);
        assertThat(stockOf(product)).isEqualTo(INITIAL_STOCK - ORDERS_PER_SET);
        assertThat(healthStatus()).isEqualTo("UP");
        awaitUnknownGauge(0);

        evidence.writeJson("baseline.json", phase("BASELINE", baselineOrders));
        evidence.writeText("baseline-health.json", health());
        evidence.writeText("baseline-prometheus.txt", prometheus());
    }

    private void fault() throws Exception {
        faultOrders = createOrders(faultUser);
        lostOrders = FAULT_POSITIONS.stream().map(faultOrders::get).toList();
        pg.dropResponseFor(lostOrders.stream().map(PaymentFlowTestSupport::providerOrderIdOf).toList());
        evidence.note("baselineOrders", baselineOrders);
        evidence.note("faultOrders", faultOrders);
        evidence.note("lostOrders", lostOrders);

        for (Long orderId : faultOrders) {
            String paymentStatus = paymentStatusOf(confirm(faultUser, orderId));
            if (lostOrders.contains(orderId)) {
                assertThat(paymentStatus).isEqualTo("UNKNOWN");
                // PG 는 거래를 최종 SUCCESS 로 저장했다. callback 은 관찰 시점까지 보내지 않는다.
                pg.setStatus(pgKeyOf(orderId), "SUCCESS");
            } else {
                assertThat(paymentStatus).isEqualTo("PENDING");
                settleAndNotify(orderId);
            }
        }

        Map<String, Long> attempts = attemptStatusCounts(faultOrders);
        assertThat(attempts.getOrDefault("SUCCEEDED", 0L)).isEqualTo(ORDERS_PER_SET - lostOrders.size());
        assertThat(attempts.getOrDefault("UNKNOWN", 0L)).isEqualTo(lostOrders.size());
        assertThat(pgSuccessCount(faultOrders)).isEqualTo(ORDERS_PER_SET);
        assertThat(pg.transactionCount()).isEqualTo(2 * ORDERS_PER_SET);
        assertThat(stockOf(product)).isEqualTo(INITIAL_STOCK - 2 * ORDERS_PER_SET);
        // 의존 서비스는 내려가지 않았다. health 는 UP 인데 업무는 끝나지 않았다.
        assertThat(healthStatus()).isEqualTo("UP");
        awaitUnknownGauge(lostOrders.size());
    }

    // 진단은 읽기만 한다. 상태를 바꾸는 reconcile 은 fault 증거를 저장한 뒤에야 호출한다.
    private void diagnoseAndPreserve() {
        List<Map<String, Object>> before = orderStates(lostOrders);
        Map<String, String> pgStatuses = lostOrders.stream().collect(Collectors.toMap(
            PaymentFlowTestSupport::providerOrderIdOf,
            orderId -> pg.transactionsOf(providerOrderIdOf(orderId)).get(0).status(),
            (first, second) -> first,
            LinkedHashMap::new
        ));
        Set<String> lostProviderIds = pgStatuses.keySet();
        List<Captured> createUnknown = logs.events("payment.create.result").stream()
            .filter(event -> lostProviderIds.contains(event.keyValues().get("providerOrderId")))
            .toList();
        List<Captured> applied = boundaryEventsOf("payment.apply.committed", lostProviderIds).stream()
            .filter(event -> "SUCCEEDED".equals(event.keyValues().get("to")))
            .toList();
        List<Captured> rolledBack = boundaryEventsOf("payment.apply.rolled_back", lostProviderIds);

        // 판정은 관찰에서 계산한다. 경계 로그가 없으면 두 번째·세 번째 가설을 가를 수 없어 "미확정"이다.
        boolean boundaryLogged = createUnknown.size() == lostOrders.size();
        boolean allPgSuccess = pgStatuses.values().stream().allMatch("SUCCESS"::equals);
        boolean createLost = boundaryLogged && createUnknown.stream().allMatch(event ->
            "UNKNOWN".equals(event.keyValues().get("result")) && Set.of("IO", "TIMEOUT").contains(event.keyValues().get("cause")));
        List<Map<String, Object>> hypotheses = List.of(
            hypothesis("PG 처리 지연", "같은 거래 조회가 PENDING", "PG 거래 상태 " + pgStatuses.values(),
                pgStatuses.containsValue("PENDING") ? "지지" : "기각"),
            hypothesis("PG 성공 응답 유실", "PG SUCCESS, 내부 UNKNOWN, create 경계 로그 UNKNOWN(IO·TIMEOUT), 적용 시도 없음",
                "create UNKNOWN " + createUnknown.size() + "건 " + createUnknown.stream().map(e -> e.keyValues().get("cause")).toList()
                    + ", PG SUCCESS " + allPgSuccess + ", 적용 commit " + applied.size() + "건",
                !boundaryLogged ? "미확정" : createLost && allPgSuccess && applied.isEmpty() ? "지지" : "기각"),
            hypothesis("성공 결과의 내부 반영 실패", "적용 시도 후 rollback 로그, 예약·Outbox 불일치",
                "apply.rolled_back " + rolledBack.size() + "건, 적용 commit " + applied.size() + "건",
                !boundaryLogged ? "미확정" : rolledBack.isEmpty() ? "기각" : "지지")
        );
        assertThat(hypotheses).extracting(h -> h.get("verdict")).containsExactly("기각", "지지", "기각");
        assertThat(createUnknown).allSatisfy(event -> assertThat(event.keyValues()).containsEntry("cause", "IO"));
        assertThat(orderStates(lostOrders)).isEqualTo(before);

        Map<String, Object> fault = phase("FAULT", faultOrders);
        fault.put("lostOrders", lostOrders);
        fault.put("pgStatusesOfLost", pgStatuses);
        evidence.writeJson("fault.json", fault);
        evidence.writeText("fault-health.json", health());
        evidence.writeText("fault-prometheus.txt", prometheus());
        evidence.writeJson("fault-logs.json", logs.all());
        evidence.writeJson("diagnosis.json", Map.of("observedAt", Instant.now(), "hypotheses", hypotheses));
        faultFileHash = evidence.sha256("fault.json");
        evidence.note("faultFileSha256", faultFileHash);
    }

    private void recover() throws Exception {
        // 새 실험 입력을 멈추고 주입을 해제한 뒤, 같은 3건만 본인 reconcile API 로 조정한다.
        pg.clearDropResponse();
        assertThat(pg.isDroppingResponses()).isFalse();
        int createCallsBefore = pg.createCalls();
        int transactionsBefore = pg.transactionCount();

        for (Long orderId : lostOrders) {
            ResponseEntity<String> reconciled = post("/api/v1/orders/" + orderId + "/payment/reconcile", faultUser, null);
            assertThat(reconciled.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(paymentStatusOf(reconciled)).isEqualTo("SUCCEEDED");
        }

        // 반복한 reconcile·알림은 같은 업무 사실로 수렴하고, 다른 사용자는 남의 주문을 조정할 수 없다.
        Long repeated = lostOrders.get(0);
        long outboxRowsBefore = outboxRowsOf(repeated);
        assertThat(post("/api/v1/orders/" + repeated + "/payment/reconcile", faultUser, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        notifyCallback(repeated);
        assertThat(outboxRowsOf(repeated)).isEqualTo(outboxRowsBefore).isEqualTo(1);
        assertThat(post("/api/v1/orders/" + repeated + "/payment/reconcile", baselineUser, null).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        Map<String, Long> attempts = attemptStatusCounts(faultOrders);
        assertThat(attempts.getOrDefault("SUCCEEDED", 0L)).isEqualTo(ORDERS_PER_SET);
        assertThat(attempts.getOrDefault("UNKNOWN", 0L)).isZero();
        assertThat(pgSuccessCount(faultOrders)).isEqualTo(ORDERS_PER_SET);
        assertThat(pg.createCalls()).isEqualTo(createCallsBefore);
        assertThat(pg.duplicateCreates()).isZero();
        assertThat(pg.transactionCount()).isEqualTo(transactionsBefore);
        // 재고는 결제 시작 때 이미 차감했다. 성공 확정으로 더 차감하지 않는다.
        assertThat(stockOf(product)).isEqualTo(INITIAL_STOCK - 2 * ORDERS_PER_SET);
        assertThat(balanceOf(faultUser)).isEqualTo(CHARGED_POINTS);
        assertThat(couponCountOf(faultUser)).isEqualTo(faultUserCouponCount);
        awaitUnknownGauge(0);

        Map<String, Object> recovery = phase("RECOVERY", faultOrders);
        recovery.put("createCallsDuringRecovery", pg.createCalls() - createCallsBefore);
        recovery.put("duplicateCreates", pg.duplicateCreates());
        recovery.put("faultUserPoints", balanceOf(faultUser));
        recovery.put("faultUserCoupons", couponCountOf(faultUser));
        evidence.writeJson("recovery.json", recovery);
        evidence.writeText("recovery-health.json", health());
        evidence.writeText("recovery-prometheus.txt", prometheus());
    }

    private void followUp(TopicReader reader, StreamerProcess streamer) throws Exception {
        List<Long> allOrders = new ArrayList<>(baselineOrders);
        allOrders.addAll(faultOrders);

        // 실제 relay(스케줄러)·Kafka·streamer 가 처리할 때까지 기다린다.
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
            "select count(*) from outbox where status = 'PUBLISHED'", Long.class)).isEqualTo(allOrders.size()));
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
            "select count(*) from product_purchase_activity", Long.class)).as(streamer.logTail()).isEqualTo(allOrders.size()));
        assertThat(jdbcTemplate.queryForList(
            "select order_id from product_purchase_activity where product_id = ? and quantity = 1", Long.class, product.getId()))
            .containsExactlyInAnyOrderElementsOf(allOrders);

        // 복구 주문의 trace: 원 결제(create) trace 와 복구(reconcile) trace 는 다르고, 복구 trace 가 Kafka·consumer 까지 이어진다.
        Map<String, ConsumerRecord<String, byte[]>> recordByKey = reader.awaitRecords(allOrders.size(), WAIT).stream()
            .collect(Collectors.toMap(ConsumerRecord::key, record -> record, (first, second) -> first));
        List<Map<String, Object>> traces = new ArrayList<>();
        for (Long orderId : lostOrders) {
            String createTrace = traceIdOf(pgCreateRequestOf(orderId).headers().get("traceparent"));
            String createLogTrace = logs.events("payment.create.result").stream()
                .filter(event -> providerOrderIdOf(orderId).equals(event.keyValues().get("providerOrderId")))
                .findFirst().orElseThrow().mdc().get("traceId");
            String outboxTrace = traceIdOf(jdbcTemplate.queryForObject(
                "select trace_parent from outbox where order_id = ?", String.class, orderId));
            String kafkaTrace = traceIdOf(headerOf(recordByKey.get(String.valueOf(orderId)), "traceparent"));

            assertThat(createTrace).isEqualTo(createLogTrace);
            assertThat(kafkaTrace).isEqualTo(outboxTrace);
            assertThat(outboxTrace).isNotEqualTo(createTrace);
            assertThat(streamer.logContains(outboxTrace)).as(streamer.logTail()).isTrue();
            traces.add(Map.of("orderId", orderId, "createTraceId", createTrace, "recoveryTraceId", outboxTrace, "kafkaTraceId", kafkaTrace));
        }

        // 고정한 입력으로 새 snapshot 을 만들고 공개 API 로 점수를 확인한다. 이 주의 입력은 baseline 10 + 장애 집합 10 = 20 이다.
        BatchProcess.Result batch = BatchProcess.runWeeklyRanking(PERIOD_START, 1);
        assertThat(batch.succeeded()).as(batch.logTail()).isTrue();
        JsonNode ranking = json(http.getForEntity("/api/v1/rankings/weekly?periodStart=" + PERIOD_START, String.class)).get("data");
        JsonNode top = ranking.get("items").get(0);
        assertThat(top.get("productId").asLong()).isEqualTo(product.getId());
        assertThat(top.get("score").asLong()).isEqualTo(allOrders.size());
        assertThat(top.get("rank").asInt()).isEqualTo(1);

        // fault 기록이 복구 뒤에도 바뀌지 않았다.
        assertThat(evidence.sha256("fault.json")).isEqualTo(faultFileHash);

        Map<String, Object> downstream = new LinkedHashMap<>();
        downstream.put("observedAt", Instant.now());
        downstream.put("outbox", jdbcTemplate.queryForList("select event_id, order_id, status, attempt_count, trace_parent from outbox order by order_id"));
        downstream.put("purchaseActivities", jdbcTemplate.queryForList(
            "select order_id, product_id, quantity, confirmed_at from product_purchase_activity order by order_id"));
        downstream.put("traces", traces);
        downstream.put("ranking", objectMapper.convertValue(ranking, Map.class));
        evidence.writeJson("downstream.json", downstream);
    }

    // ---- 고객 API ----

    private List<Long> createOrders(User user) throws Exception {
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < ORDERS_PER_SET; i++) {
            ResponseEntity<String> created = post("/api/v1/orders", user,
                "{\"items\":[{\"productId\":" + product.getId() + ",\"quantity\":1}]}");
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
            orderIds.add(json(created).get("data").get("id").asLong());
        }
        return orderIds;
    }

    private ResponseEntity<String> confirm(User user, Long orderId) {
        return post("/api/v1/orders/" + orderId + "/confirm", user,
            "{\"paymentAttemptKey\":\"incident-" + orderId + "\",\"cardType\":\"TEST\",\"cardNo\":\"" + CARD_NO + "\"}");
    }

    // PG 가 거래를 최종 SUCCESS 로 처리하고, 정상 경로대로 결과 알림을 보낸다.
    private void settleAndNotify(Long orderId) {
        pg.setStatus(pgKeyOf(orderId), "SUCCESS");
        notifyCallback(orderId);
    }

    private void notifyCallback(Long orderId) {
        String body = "{\"providerOrderId\":\"" + providerOrderIdOf(orderId) + "\",\"transactionKey\":\"" + pgKeyOf(orderId)
            + "\",\"status\":\"SUCCESS\"}";
        assertThat(post("/api/v1/payments/callback", null, body).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private long couponCountOf(User user) throws Exception {
        HttpEntity<Void> request = new HttpEntity<>(headers(user));
        ResponseEntity<String> response = http.exchange("/api/v1/coupons", HttpMethod.GET, request, String.class);
        return json(response).get("data").get("totalElements").asLong();
    }

    private ResponseEntity<String> post(String path, User user, String body) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(user)), String.class);
    }

    private HttpHeaders headers(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            headers.set("X-USER-ID", String.valueOf(user.getId()));
        }
        return headers;
    }

    private String paymentStatusOf(ResponseEntity<String> response) throws Exception {
        return json(response).get("data").get("payment").get("status").asText();
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    // ---- 관측: 실제 Actuator 응답 ----

    private String health() {
        return http.getRestTemplate().getForObject("http://localhost:" + managementPort + "/actuator/health", String.class);
    }

    private String healthStatus() throws Exception {
        return objectMapper.readTree(health()).get("status").asText();
    }

    private String prometheus() {
        return http.getRestTemplate().getForObject("http://localhost:" + managementPort + "/actuator/prometheus", String.class);
    }

    // gauge 는 DB 를 주기적으로 읽어 늦게 바뀐다. 유한한 대기 안에서 실제 Prometheus 응답의 값이 바뀌는지 본다.
    private void awaitUnknownGauge(int expected) {
        Pattern line = Pattern.compile("^commerce_payment_unresolved\\{[^}]*status=\"UNKNOWN\"[^}]*} (\\S+)$", Pattern.MULTILINE);
        await().atMost(WAIT).untilAsserted(() -> {
            Matcher matcher = line.matcher(prometheus());
            assertThat(matcher.find()).isTrue();
            assertThat(Double.parseDouble(matcher.group(1))).isEqualTo(expected);
        });
    }

    // ---- DB·PG 상태(읽기 전용) ----

    private List<Map<String, Object>> orderStates(List<Long> orderIds) {
        List<Map<String, Object>> states = new ArrayList<>();
        for (Long orderId : orderIds) {
            Map<String, Object> state = new LinkedHashMap<>(jdbcTemplate.queryForMap(
                "select o.id as order_id, o.status as order_status, o.confirmed_at, a.attempt_key, a.provider_order_id, "
                    + "a.transaction_key, a.status as attempt_status from orders o join payment_attempts a on a.order_id = o.id "
                    + "where o.id = ?", orderId));
            List<FakeTransactionView> transactions = pg.transactionsOf(providerOrderIdOf(orderId)).stream()
                .map(transaction -> new FakeTransactionView(transaction.key(), transaction.status(), transaction.amount()))
                .toList();
            state.put("pgTransactions", transactions);
            states.add(state);
        }
        return states;
    }

    private record FakeTransactionView(String key, String status, long amount) {}

    private Map<String, Long> attemptStatusCounts(List<Long> orderIds) {
        return orderStates(orderIds).stream()
            .collect(Collectors.groupingBy(state -> String.valueOf(state.get("attempt_status")), Collectors.counting()));
    }

    private long pgSuccessCount(List<Long> orderIds) {
        return orderIds.stream()
            .filter(orderId -> pg.transactionsOf(providerOrderIdOf(orderId)).stream().anyMatch(tx -> tx.status().equals("SUCCESS")))
            .count();
    }

    private long outboxRowsOf(Long orderId) {
        return jdbcTemplate.queryForObject("select count(*) from outbox where order_id = ?", Long.class, orderId);
    }

    private RecordedRequest pgCreateRequestOf(Long orderId) {
        return pg.requests().stream()
            .filter(request -> request.method().equals("POST") && request.body().contains(providerOrderIdOf(orderId)))
            .findFirst().orElseThrow();
    }

    private List<Captured> boundaryEventsOf(String event, Set<String> providerOrderIds) {
        return logs.events(event).stream()
            .filter(captured -> providerOrderIds.contains(captured.keyValues().get("providerOrderId")))
            .toList();
    }

    private Map<String, Object> phase(String name, List<Long> orderIds) {
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("phase", name);
        phase.put("observedAt", Instant.now());
        phase.put("orders", orderStates(orderIds));
        phase.put("attemptStatusCounts", attemptStatusCounts(orderIds));
        phase.put("pgSuccessTransactions", pgSuccessCount(orderIds));
        phase.put("pgTransactionsTotal", pg.transactionCount());
        phase.put("pgCreateCalls", pg.createCalls());
        phase.put("pgDuplicateCreates", pg.duplicateCreates());
        phase.put("stock", stockOf(product));
        return phase;
    }

    private static Map<String, Object> hypothesis(String name, String expected, String actual, String verdict) {
        Map<String, Object> hypothesis = new LinkedHashMap<>();
        hypothesis.put("hypothesis", name);
        hypothesis.put("expected", expected);
        hypothesis.put("actual", actual);
        hypothesis.put("verdict", verdict);
        return hypothesis;
    }

    private static String headerOf(ConsumerRecord<String, byte[]> record, String name) {
        assertThat(record).isNotNull();
        Header header = record.headers().lastHeader(name);
        assertThat(header).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String traceIdOf(String traceparent) {
        assertThat(traceparent).isNotNull();
        Matcher matcher = TRACEPARENT.matcher(traceparent);
        assertThat(matcher.matches()).as(traceparent).isTrue();
        return matcher.group(1);
    }
}
