package com.loopers.e2e;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.PaymentGateway;
import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.application.order.PaymentGatewayResult.Outcome;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.e2e.pg.FakePgServer;
import com.loopers.infrastructure.order.PgClient;
import com.loopers.support.ApiTestClient;
import com.loopers.support.MutableClock;
import com.loopers.support.fixture.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * W10 사고 실험: health 는 UP 인데 결제가 "확인 중"에 머문다. PG 는 SUCCESS 거래를 저장했지만 생성 응답을 잃어 내부 주문이 결제 대기(거래 키 없음)로 남는 경계를 통제한다.
 * 한 lifecycle(같은 DB·같은 가짜 PG 거래 상태)에서 BASELINE → FAULT → 증거 보존·진단 → RECOVERY 를 잇는다.
 * <ul>
 *   <li>PG: 실제 PgClient → 거래를 저장하는 HTTP 가짜 PG(FakePgServer). 정상은 PENDING 접수 → settle → 실제 callback → 권위 조회로 확정.</li>
 *   <li>장애: 장애 집합 10건 중 3·6·9번째 주문(실제 ID)만 SUCCESS 저장 뒤 빈 본문 응답 → PgClient UNKNOWN. 이 3건은 callback 을 보내지 않는다.</li>
 *   <li>자동 조회·결제 대기 만료는 test 프로필 값(1h)으로 관찰 동안 끼어들지 않는다 (제품 기능을 지우지 않는다). 시작 때 그 값을 확인한다.</li>
 *   <li>복구: 응답 유실을 끄고 같은 3건에 기존 본인 주문 결제 대기 해소 API 를 부른다. PG create 는 늘지 않는다.</li>
 * </ul>
 * commerce DB 에 상태를 직접 쓰지 않는다. 상태는 모두 실제 API·PG 경로로 만들고 매번 새 DB 조회로 확인한다.
 * 실행: ./gradlew :tests:commerce-e2e:test --tests '*CheckoutIncidentTest' (Docker 필요).
 */
class CheckoutIncidentTest {
    private static final String TOPIC = "order-events-e2e-incident";
    private static final String CARD_NO = "1234-5678-9814-1451";
    private static final long PRICE = 1_000L;
    private static final int STOCK = 25;
    private static final int SET_SIZE = 10;
    private static final List<Integer> FAULT_POSITIONS = List.of(2, 5, 8);
    private static final long BALANCE = 10_000L;
    private static final Duration METRIC_WAIT = Duration.ofSeconds(5);
    private static final Pattern AWAITING_LINE = Pattern.compile("^commerce_payment_awaiting\\{([^}]*)}\\s+(\\S+)$", Pattern.MULTILINE);

    private static final Instant T_BASELINE = Instant.parse("2026-11-16T01:00:00Z");
    private static final Instant T_FAULT = Instant.parse("2026-11-16T02:00:00Z");
    private static final Instant T_RECOVERY = Instant.parse("2026-11-16T03:00:00Z");
    private static final MutableClock API_CLOCK = new MutableClock(Instant.parse("2026-11-16T00:30:00Z"), ZoneId.of("Asia/Seoul"));

    @TestConfiguration
    static class ApiClock {
        @Bean
        @Primary
        MutableClock apiMutableClock() {
            return API_CLOCK;
        }
    }

    /** 진단 판정. 조치 전에 적은 경쟁 가설과 같은 순서. */
    enum Verdict { PG_DELAYED, RESPONSE_LOST, APPLY_FAILED, UNDETERMINED }

    /** 단계 관찰: 대상 주문 집합 안의 내부 확정·결제 대기 수, 그 집합의 PG SUCCESS 거래 수, 누적 PG 거래 수, health. */
    record StageView(String stage, Instant observedAt, long confirmed, long awaiting, long pgSuccess, int cumulativePgTransactions, String health) {
    }

    private ConfigurableApplicationContext api;
    private FakePgServer pg;
    private ApiTestClient client;
    private E2eDb db;
    private UserModel user;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> boundaryLogs = new ListAppender<>();
    private final List<Logger> observedLoggers = new ArrayList<>();
    private int managementPort;

    @AfterEach
    void tearDown() {
        observedLoggers.forEach(logger -> logger.detachAppender(boundaryLogs));
        boundaryLogs.stop();
        E2eContexts.closeQuietly(api);
        if (pg != null) {
            pg.close();
        }
        http.close();
    }

    @DisplayName("[W10] health UP 인 채 PG SUCCESS 응답 유실로 결제 대기에 머문 3건을, 증거로 원인을 가린 뒤 추가 결제 없이 같은 주문으로 복구한다")
    @Test
    void diagnosesLostPgResponseAndRecoversSameOrdersWithoutNewPayment() throws Exception {
        // ---- 준비: 컨테이너·가짜 PG·api (실제 PgClient). 포트는 미리 비어 있음을 확인한 것을 쓴다 ----
        E2eContexts.startContainers();
        db = new E2eDb();
        pg = FakePgServer.start();
        int apiPort = freePort();
        api = E2eContexts.startApi(TOPIC, List.of(
            "server.port=" + apiPort,
            "test.pg.fake-gateway.enabled=false",
            "pg.base-url=" + pg.baseUrl(),
            "pg.callback-url=http://localhost:" + apiPort + "/api/v1/payments/callback",
            "commerce.metrics.payment-awaiting.refresh-interval=200ms"
        ), ApiClock.class);
        managementPort = Integer.parseInt(api.getEnvironment().getRequiredProperty("local.management.port"));
        assertThat(E2eContexts.port(api)).isEqualTo(apiPort);
        assertThat(api.getBean(PaymentGateway.class)).as("실제 PgClient 가 HTTP 가짜 PG 를 부른다").isInstanceOf(PgClient.class);
        assertThat(api.getEnvironment().getRequiredProperty("pg.lookup-delay")).as("자동 백업 조회는 관찰 동안 끼어들지 않는다").isEqualTo("1h");
        assertThat(api.getEnvironment().getRequiredProperty("pg.payment-expiry-interval")).as("결제 대기 만료 job 도").isEqualTo("1h");
        observe(OrderFacade.class, PgClient.class, FakePgServer.class);

        Fixtures fixtures = api.getBean(Fixtures.class);
        fixtures.catalogRevision();
        user = fixtures.userWithBalance(BALANCE);
        UserModel otherUser = fixtures.user();
        ProductModel product = fixtures.product(fixtures.brand("브랜드").getId(), "상품", PRICE, STOCK);
        client = new ApiTestClient(new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + apiPort)),
            api.getBean(ObjectMapper.class));

        assertThat(get("/actuator/env").statusCode()).as("env 는 노출하지 않는다").isEqualTo(404);
        assertThat(get("/actuator/heapdump").statusCode()).as("heapdump 도").isEqualTo(404);

        API_CLOCK.set(T_BASELINE);
        List<Long> baseline = draftOrders(product);
        List<Long> faultSet = draftOrders(product);
        List<Long> faulted = FAULT_POSITIONS.stream().map(faultSet::get).toList();
        List<Long> faultHealthy = faultSet.stream().filter(id -> !faulted.contains(id)).toList();
        assertThat(baseline).doesNotContainAnyElementsOf(faultSet);

        // ---- BASELINE: 응답 유실 OFF. 정상 10건 PENDING 접수 → settle SUCCESS → callback → 확정 ----
        assertThat(pg.isDropping()).isFalse();
        for (Long orderId : baseline) {
            assertThat(confirm(orderId).path("status").asText()).isEqualTo("AWAITING_PAYMENT");
            assertThat(pg.settle(orderId, FakePgServer.Status.SUCCESS)).isEqualTo(200);
        }
        StageView baselineView = view("BASELINE", baseline);
        assertThat(baselineView).extracting(StageView::confirmed, StageView::awaiting, StageView::pgSuccess, StageView::cumulativePgTransactions)
            .containsExactly(10L, 0L, 10L, 10);
        assertThat(baselineView.health()).isEqualTo("UP");
        assertThat(awaitAwaitingGauges(0, 0)).containsExactly(entry("known", 0.0), entry("missing", 0.0));
        assertThat(db.stockOf(product.getId())).isEqualTo(STOCK - SET_SIZE);

        // ---- FAULT: 고정한 3건만 SUCCESS 저장 뒤 응답 유실, callback 보류. 나머지 7건은 정상 경로 ----
        API_CLOCK.set(T_FAULT);
        Map<Long, JsonNode> faultConfirmResponses = new LinkedHashMap<>();
        pg.dropResponsesFor(faulted);
        try {
            for (Long orderId : faultSet) {
                faultConfirmResponses.put(orderId, confirm(orderId));
            }
        } finally {
            pg.stopDropping(); // 내가 켠 주입만 끈다. 거래·기록은 그대로
        }
        for (Long orderId : faultHealthy) {
            assertThat(faultConfirmResponses.get(orderId).path("status").asText()).isEqualTo("AWAITING_PAYMENT");
            assertThat(pg.settle(orderId, FakePgServer.Status.SUCCESS)).isEqualTo(200);
        }
        for (Long orderId : faulted) {
            assertThat(faultConfirmResponses.get(orderId).path("status").asText()).as("고객에게는 결제 확인 중").isEqualTo("AWAITING_PAYMENT");
        }

        StageView faultView = view("FAULT", faultSet);
        assertThat(faultView).extracting(StageView::confirmed, StageView::awaiting, StageView::pgSuccess, StageView::cumulativePgTransactions)
            .containsExactly(7L, 3L, 10L, 20);
        assertThat(faultView.health()).as("의존 서비스가 내려가지 않았으므로 health 는 UP, 업무는 미완료").isEqualTo("UP");
        Map<String, Double> faultGauges = awaitAwaitingGauges(0, 3);
        assertThat(faultGauges).as("미확인(거래 키 없음) 3, 접수 대기 0").containsExactly(entry("known", 0.0), entry("missing", 3.0));
        for (Long orderId : faulted) {
            Map<String, Object> order = db.orderPayment(orderId);
            assertThat(order.get("status")).isEqualTo("AWAITING_PAYMENT");
            assertThat(order.get("transaction_key")).as("응답을 잃어 거래 키를 모른다").isNull();
            assertThat(order.get("payment_attempt_key")).isEqualTo("attempt-" + orderId);
            assertThat(order.get("confirmed_at")).isNull();
            assertThat(reservations(orderId)).as("결제 대기 동안 예약 유지").containsExactly("RESERVE");
            assertThat(db.outboxOfOrder(orderId)).isEmpty();
            FakePgServer.Transaction transaction = pg.transaction(orderId).orElseThrow();
            assertThat(transaction.status()).isEqualTo(FakePgServer.Status.SUCCESS);
            assertThat(transaction.amount()).isEqualTo(((Number) order.get("total_amount")).longValue());
            assertThat(transaction.userId()).isEqualTo(String.valueOf(user.getId()));
            assertThat(pg.createCount(orderId)).isEqualTo(1);
        }
        assertThat(pg.callbacks()).extracting(FakePgServer.Callback::pgOrderId)
            .as("응답 유실 3건에는 callback 이 가지 않았다")
            .doesNotContainAnyElementsOf(faulted.stream().map(id -> "ORDER-" + id).toList());
        assertThat(db.stockOf(product.getId())).isEqualTo(STOCK - 2 * SET_SIZE);

        // ---- 증거 보존·진단: 읽기만 한다. 복구 API 는 아직 부르지 않는다 ----
        Map<Long, Map<String, Object>> beforeDiagnosis = new LinkedHashMap<>();
        faulted.forEach(orderId -> beforeDiagnosis.put(orderId, db.orderPayment(orderId)));
        int lookupsBeforeDiagnosis = pg.lookups().size();
        PaymentGateway gateway = api.getBean(PaymentGateway.class);
        Map<Long, PaymentGatewayResult> diagnosis = new LinkedHashMap<>();
        for (Long orderId : faulted) {
            diagnosis.put(orderId, gateway.find(user.getId(), orderId));
        }
        Map<Long, Verdict> verdicts = new LinkedHashMap<>();
        for (Long orderId : faulted) {
            verdicts.put(orderId, judge(orderId, diagnosis.get(orderId)));
            PaymentGatewayResult found = diagnosis.get(orderId);
            assertThat(found.outcome()).isEqualTo(Outcome.SUCCESS);
            assertThat(found.transactionKey()).isEqualTo(pg.transaction(orderId).orElseThrow().transactionKey());
        }
        assertThat(verdicts).as("세 건 모두 PG 성공 응답 유실 (처리 지연·내부 반영 실패가 아님)")
            .containsOnlyKeys(faulted).allSatisfy((orderId, verdict) -> assertThat(verdict).isEqualTo(Verdict.RESPONSE_LOST));
        faulted.forEach(orderId -> assertThat(db.orderPayment(orderId)).as("진단 조회는 상태를 바꾸지 않는다").isEqualTo(beforeDiagnosis.get(orderId)));
        assertThat(pg.lookups()).hasSize(lookupsBeforeDiagnosis + faulted.size());
        assertThat(awaitAwaitingGauges(0, 3)).as("진단 뒤에도 지표 그대로").containsExactly(entry("known", 0.0), entry("missing", 3.0));

        int lookupsBeforeForeign = pg.lookups().size();
        // 기존 계약: 남의 주문은 ER-06 NOT_OWNER (403, 존재를 숨기지 않는다)
        client.post(resolvePath(faulted.get(0)), otherUser.getId(), null).assertError(HttpStatus.FORBIDDEN, "NOT_OWNER");
        assertThat(pg.lookups()).as("다른 사용자의 조정은 PG 를 부르지 않는다").hasSize(lookupsBeforeForeign);
        assertThat(db.orderPayment(faulted.get(0))).isEqualTo(beforeDiagnosis.get(faulted.get(0)));

        // ---- RECOVERY: 응답 유실 OFF, 같은 거래·DB. 같은 3건에 기존 결제 대기 해소 API ----
        API_CLOCK.set(T_RECOVERY);
        assertThat(pg.isDropping()).isFalse();
        int createsBeforeRecovery = pg.createAttempts().size();
        for (Long orderId : faulted) {
            assertThat(client.post(resolvePath(orderId), user.getId(), null).assertSuccess(HttpStatus.OK).data().path("status").asText())
                .isEqualTo("CONFIRMED");
        }
        Map<Long, Map<String, Object>> afterFirstRecovery = new LinkedHashMap<>();
        faulted.forEach(orderId -> afterFirstRecovery.put(orderId, db.orderPayment(orderId)));
        for (Long orderId : faulted) { // 반복 조정·알림은 같은 사실로 수렴한다
            assertThat(client.post(resolvePath(orderId), user.getId(), null).assertSuccess(HttpStatus.OK).data().path("status").asText())
                .isEqualTo("CONFIRMED");
            assertThat(pg.sendCallback(orderId)).isEqualTo(200);
            assertThat(db.orderPayment(orderId)).isEqualTo(afterFirstRecovery.get(orderId));
        }

        StageView recoveryView = view("RECOVERY", faultSet);
        assertThat(recoveryView).extracting(StageView::confirmed, StageView::awaiting, StageView::pgSuccess, StageView::cumulativePgTransactions)
            .containsExactly(10L, 0L, 10L, 20);
        assertThat(recoveryView.health()).isEqualTo("UP");
        assertThat(pg.createAttempts()).as("복구 중 PG create 추가 0").hasSize(createsBeforeRecovery);
        assertThat(pg.duplicateCreates()).isEmpty();
        for (Long orderId : faultSet) {
            Map<String, Object> order = db.orderPayment(orderId);
            FakePgServer.Transaction transaction = pg.transaction(orderId).orElseThrow();
            assertThat(order.get("status")).isEqualTo("CONFIRMED");
            assertThat(order.get("transaction_key")).as("같은 PG 거래로 확정").isEqualTo(transaction.transactionKey());
            assertThat(transaction.status()).isEqualTo(FakePgServer.Status.SUCCESS);
            assertThat(((Number) order.get("paid_amount")).longValue()).isEqualTo(transaction.amount()).isEqualTo(PRICE);
            assertThat(pg.createCount(orderId)).isEqualTo(1);
            assertThat(reservations(orderId)).as("성공 때 추가 차감·반환 없음").containsExactly("RESERVE");
            assertThat(db.outboxOfOrder(orderId)).as("확정 사실은 주문당 Outbox 한 행").hasSize(1);
        }
        assertThat(db.stockOf(product.getId())).isEqualTo(STOCK - 2 * SET_SIZE);
        assertThat(db.pointOf(user.getId())).as("카드 결제 복구는 포인트를 바꾸지 않는다").isEqualTo(BALANCE);
        assertThat(awaitAwaitingGauges(0, 0)).containsExactly(entry("known", 0.0), entry("missing", 0.0));

        LoggerFactory.getLogger(CheckoutIncidentTest.class).info(
            "[W10 관찰] baseline={}, faultSet={}, faulted={}, verdicts={}, views={}, createsTotal={}, lookupsTotal={}, callbacksTotal={}",
            baseline, faultSet, faulted, verdicts, List.of(baselineView, faultView, recoveryView),
            pg.createAttempts().size(), pg.lookups().size(), pg.callbacks().size());
    }

    // ---- 주문·PG ----

    private List<Long> draftOrders(ProductModel product) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < SET_SIZE; i++) {
            ids.add(client.post("/api/v1/orders", user.getId(), Map.of("items", List.of(Map.of("productId", product.getId(), "quantity", 1))))
                .assertSuccess(HttpStatus.CREATED).data().path("id").asLong());
        }
        return ids;
    }

    private JsonNode confirm(Long orderId) {
        return client.post("/api/v1/orders/" + orderId + "/confirm", user.getId(),
                Map.of("paymentAttemptKey", "attempt-" + orderId, "cardType", "SAMSUNG", "cardNumber", CARD_NO))
            .assertSuccess(HttpStatus.OK).data();
    }

    private static String resolvePath(Long orderId) {
        return "/api/v1/orders/" + orderId + "/payment/resolve-awaiting-payment";
    }

    private List<Object> reservations(Long orderId) {
        return db.ledgerOfOrder(orderId).stream().map(row -> row.get("type")).toList();
    }

    private StageView view(String stage, List<Long> orderIds) throws IOException, InterruptedException {
        long confirmed = orderIds.stream().filter(id -> "CONFIRMED".equals(db.orderPayment(id).get("status"))).count();
        long awaiting = orderIds.stream().filter(id -> "AWAITING_PAYMENT".equals(db.orderPayment(id).get("status"))).count();
        long pgSuccess = orderIds.stream()
            .filter(id -> pg.transaction(id).map(transaction -> transaction.status() == FakePgServer.Status.SUCCESS).orElse(false)).count();
        return new StageView(stage, Instant.now(), confirmed, awaiting, pgSuccess, pg.transactions().size(), health());
    }

    // ---- 진단 ----

    /**
     * 경쟁 가설 판정. 조회 결과만이 아니라 실제 경계 기록(요청 결과 로그·PgClient 응답 처리 로그·반영 TX 결말)까지 맞아야 응답 유실이다. 부족하면 UNDETERMINED.
     * PG_DELAYED: 권위 조회가 PENDING. APPLY_FAILED: 반영 TX 가 ROLLED_BACK. RESPONSE_LOST: 요청 결과 UNKNOWN + 빈 본문 기록 + 권위 조회 SUCCESS + rollback 없음.
     */
    private Verdict judge(Long orderId, PaymentGatewayResult lookup) {
        List<Map<String, Object>> facade = keyValues(orderId);
        boolean rolledBack = facade.stream().anyMatch(kv -> "payment.apply".equals(kv.get("event")) && "ROLLED_BACK".equals(kv.get("tx")));
        if (rolledBack) {
            return Verdict.APPLY_FAILED;
        }
        if (lookup.outcome() == Outcome.PENDING) {
            return Verdict.PG_DELAYED;
        }
        boolean requestUnknown = facade.stream()
            .anyMatch(kv -> "payment.request.result".equals(kv.get("event")) && "UNKNOWN".equals(kv.get("outcome")));
        boolean emptyBody = boundaryLogs.list.stream()
            .anyMatch(event -> event.getLoggerName().equals(PgClient.class.getName())
                && event.getFormattedMessage().startsWith("PG 응답 본문 없음")
                && event.getFormattedMessage().contains("[orderId = " + orderId + "]"));
        if (requestUnknown && emptyBody && lookup.outcome() == Outcome.SUCCESS) {
            return Verdict.RESPONSE_LOST;
        }
        return Verdict.UNDETERMINED;
    }

    /** OrderFacade 경계 로그 중 이 주문의 key-value, 남긴 순서대로. */
    private List<Map<String, Object>> keyValues(Long orderId) {
        return boundaryLogs.list.stream()
            .filter(event -> event.getLoggerName().equals(OrderFacade.class.getName()) && event.getKeyValuePairs() != null)
            .map(event -> {
                Map<String, Object> keyValues = new LinkedHashMap<>();
                for (KeyValuePair pair : event.getKeyValuePairs()) {
                    keyValues.put(pair.key, pair.value);
                }
                return keyValues;
            })
            .filter(keyValues -> orderId.equals(keyValues.get("orderId")))
            .toList();
    }

    /** 모든 context 를 띄운 뒤에 붙인다 (context 시작이 logback 설정을 다시 읽어 붙인 appender 를 떼기 때문). */
    private void observe(Class<?>... types) {
        boundaryLogs.start();
        for (Class<?> type : types) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            logger.addAppender(boundaryLogs);
            observedLoggers.add(logger);
        }
    }

    // ---- 관리 endpoint (실제 주입된 management 포트) ----

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path)).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private String health() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/actuator/health");
        return new ObjectMapper().readTree(response.body()).path("status").asText();
    }

    /** 한 번의 Prometheus 원문에서 commerce_payment_awaiting 의 pg_transaction 별 값 (known·missing 순). 줄이 없으면 그 태그는 빠진다. */
    private Map<String, Double> awaitingGauges() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Double> values = new TreeMap<>();
        Matcher matcher = AWAITING_LINE.matcher(response.body());
        while (matcher.find()) {
            for (String tag : List.of("known", "missing")) {
                if (matcher.group(1).contains("pg_transaction=\"" + tag + "\"")) {
                    values.put(tag, Double.parseDouble(matcher.group(2)));
                }
            }
        }
        return values;
    }

    /**
     * 주기 갱신 gauge 라 늦을 수 있다: METRIC_WAIT 안에 같은 scrape 의 known·missing 이 기대값이 되면 그 값을 돌려준다.
     * 한 태그만 기다리면 다른 태그가 중간 시점 값일 수 있어 둘을 함께 본다. 시간 안에 안 되면 마지막 값을 돌려줘 호출부 assert 가 실패한다.
     */
    private Map<String, Double> awaitAwaitingGauges(double known, double missing) throws IOException, InterruptedException {
        Map<String, Double> expected = Map.of("known", known, "missing", missing);
        long deadline = System.nanoTime() + METRIC_WAIT.toNanos();
        Map<String, Double> last = awaitingGauges();
        while (!last.equals(expected) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            last = awaitingGauges();
        }
        return last;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
