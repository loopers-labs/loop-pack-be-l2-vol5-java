package com.loopers.e2e;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderEventOutboxProcessor;
import com.loopers.application.order.OrderEventRelay;
import com.loopers.application.order.OrderEventSender;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OutboxRelayProperties;
import com.loopers.application.order.PaymentGateway;
import com.loopers.application.order.PaymentGatewayResult;
import com.loopers.application.order.PaymentGatewayResult.Outcome;
import com.loopers.batch.job.ranking.RankingChecksum;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingTarget;
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
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

/**
 * W10 사고 실험: health 는 UP 인데 결제가 "확인 중"에 머문다. PG 는 SUCCESS 거래를 저장했지만 생성 응답을 잃어 내부 주문이 결제 대기(거래 키 없음)로 남는 경계를 통제한다.
 * 한 lifecycle(같은 DB·같은 가짜 PG 거래 상태)에서 BASELINE → FAULT → 증거 보존·진단 → RECOVERY → 후속 결과(Outbox·Kafka·구매 활동·주간 랭킹)를 잇는다.
 * <ul>
 *   <li>PG: 실제 PgClient → 거래를 저장하는 HTTP 가짜 PG(FakePgServer). 정상은 PENDING 접수 → settle → 실제 callback → 권위 조회로 확정.</li>
 *   <li>장애: 장애 집합 10건 중 3·6·9번째 주문(실제 ID)만 SUCCESS 저장 뒤 빈 본문 응답 → PgClient UNKNOWN. 이 3건은 callback 을 보내지 않는다.</li>
 *   <li>자동 조회·결제 대기 만료는 test 프로필 값(1h)으로 관찰 동안 끼어들지 않는다 (제품 기능을 지우지 않는다). 시작 때 그 값을 확인한다.</li>
 *   <li>복구: 응답 유실을 끄고 같은 3건에 기존 본인 주문 결제 대기 해소 API 를 부른다. PG create 는 늘지 않는다.</li>
 *   <li>후속: 같은 주(2026-11-16)의 baseline·장애 집합 20건 Outbox 를 실제 relay·Kafka·consumer 로 처리하고, 새 주간 snapshot(r1)을 만들어 랭킹 API 로 읽는다.</li>
 * </ul>
 * commerce DB 에 상태를 직접 쓰지 않는다. 상태는 모두 실제 API·PG 경로로 만들고 매번 새 DB 조회로 확인한다.
 * 증거는 실행마다 build/incident-evidence/{runId}/ 에 새로 쓴다 (덮어쓰지 않음, 파일별 sha256). 통과 여부는 파일이 아니라 아래 assertion 이 정한다.
 * 실행: ./gradlew :tests:commerce-e2e:test --tests '*CheckoutIncidentTest' (Docker 필요).
 */
class CheckoutIncidentTest {
    private static final String TOPIC = "order-events-e2e-incident";
    private static final String GROUP = "purchase-activity-e2e-incident";
    private static final String CARD_NO = "1234-5678-9814-1451";
    private static final long PRICE = 1_000L;
    private static final int STOCK = 25;
    private static final int SET_SIZE = 10;
    private static final List<Integer> FAULT_POSITIONS = List.of(2, 5, 8);
    private static final long BALANCE = 10_000L;
    private static final Duration METRIC_WAIT = Duration.ofSeconds(5);
    private static final Pattern AWAITING_LINE = Pattern.compile("^commerce_payment_awaiting\\{([^}]*)}\\s+(\\S+)$", Pattern.MULTILINE);
    private static final DateTimeFormatter DB_TEXT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final LocalDate WEEK = LocalDate.parse("2026-11-16");
    private static final Instant T_BASELINE = Instant.parse("2026-11-16T01:00:00Z");
    private static final Instant T_FAULT = Instant.parse("2026-11-16T02:00:00Z");
    private static final Instant T_RECOVERY = Instant.parse("2026-11-16T03:00:00Z");
    private static final Instant T_RELAY = Instant.parse("2026-11-16T04:00:00Z");
    private static final MutableClock API_CLOCK = new MutableClock(Instant.parse("2026-11-16T00:30:00Z"), ZoneId.of("Asia/Seoul"));
    private static final MutableClock BATCH_CLOCK = new MutableClock(Instant.parse("2026-11-23T01:00:00Z"), ZoneOffset.UTC);

    @TestConfiguration
    static class ApiClock {
        @Bean
        @Primary
        MutableClock apiMutableClock() {
            return API_CLOCK;
        }
    }

    @TestConfiguration
    static class BatchClock {
        @Bean
        @Primary
        MutableClock batchMutableClock() {
            return BATCH_CLOCK;
        }
    }

    /** 진단 판정. 조치 전에 적은 경쟁 가설과 같은 순서. */
    enum Verdict { PG_DELAYED, RESPONSE_LOST, APPLY_FAILED, UNDETERMINED }

    /** 단계 관찰: 대상 주문 집합 안의 내부 확정·결제 대기 수, 그 집합의 PG SUCCESS 거래 수, 누적 PG 거래 수, health status. */
    record StageView(String stage, Instant observedAt, long confirmed, long awaiting, long pgSuccess, int cumulativePgTransactions, String health) {
    }

    /** 한 번의 Prometheus 응답 원문과 거기서 읽은 commerce_payment_awaiting 값. */
    record Scrape(String raw, Map<String, Double> awaiting) {
    }

    private ConfigurableApplicationContext api;
    private ConfigurableApplicationContext streamer;
    private ConfigurableApplicationContext batch;
    private FakePgServer pg;
    private ApiTestClient client;
    private E2eDb db;
    private UserModel user;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> boundaryLogs = new ListAppender<>();
    private final List<Logger> observedLoggers = new ArrayList<>();
    private int managementPort;
    private IncidentEvidence evidence;
    private String stage = "SETUP";

    @AfterEach
    void tearDown() {
        observedLoggers.forEach(logger -> logger.detachAppender(boundaryLogs));
        boundaryLogs.stop();
        E2eContexts.closeQuietly(batch);
        E2eContexts.closeQuietly(streamer);
        E2eContexts.closeQuietly(api);
        if (pg != null) {
            pg.close();
        }
        http.close();
    }

    @DisplayName("[W10] health UP 인 채 PG SUCCESS 응답 유실로 결제 대기에 머문 3건을, 증거로 원인을 가린 뒤 추가 결제 없이 같은 주문으로 복구하고 후속 활동·주간 랭킹까지 확인한다")
    @Test
    void diagnosesLostPgResponseAndRecoversSameOrdersWithoutNewPayment() throws Exception {
        evidence = IncidentEvidence.create(Path.of(System.getProperty("e2e.incident-evidence-dir")));
        try {
            runIncident();
        } catch (Throwable failure) {
            writeFailure(failure);
            throw failure;
        }
    }

    private void runIncident() throws Exception {
        // ---- 준비: 컨테이너·가짜 PG·api (실제 PgClient)·streamer·batch. api 포트는 미리 비어 있음을 확인한 것을 쓴다 ----
        E2eContexts.startContainers();
        db = new E2eDb();
        db.runScript(Path.of(System.getProperty("ranking.weekly-sql")));
        pg = FakePgServer.start();
        int apiPort = freePort();
        api = E2eContexts.startApi(TOPIC, List.of(
            "server.port=" + apiPort,
            "test.pg.fake-gateway.enabled=false",
            "pg.base-url=" + pg.baseUrl(),
            "pg.callback-url=http://localhost:" + apiPort + "/api/v1/payments/callback",
            "commerce.metrics.payment-awaiting.refresh-interval=200ms"
        ), ApiClock.class);
        streamer = E2eContexts.startStreamer(TOPIC, GROUP);
        batch = E2eContexts.startBatch(BatchClock.class);
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
        evidence.writeJson("00-run.json", runRecord(apiPort, product, baseline, faultSet, faulted));

        // ---- BASELINE: 응답 유실 OFF. 정상 10건 PENDING 접수 → settle SUCCESS → callback → 확정 ----
        stage = "BASELINE";
        assertThat(pg.isDropping()).isFalse();
        for (Long orderId : baseline) {
            assertThat(confirm(orderId).path("status").asText()).isEqualTo("AWAITING_PAYMENT");
            assertThat(pg.settle(orderId, FakePgServer.Status.SUCCESS)).isEqualTo(200);
        }
        StageView baselineView = view("BASELINE", baseline);
        assertThat(baselineView).extracting(StageView::confirmed, StageView::awaiting, StageView::pgSuccess, StageView::cumulativePgTransactions)
            .containsExactly(10L, 0L, 10L, 10);
        assertThat(baselineView.health()).isEqualTo("UP");
        Scrape baselineScrape = awaitAwaitingGauges(0, 0);
        assertThat(baselineScrape.awaiting()).containsExactly(entry("known", 0.0), entry("missing", 0.0));
        assertThat(db.stockOf(product.getId())).isEqualTo(STOCK - SET_SIZE);
        writeStage("01-baseline", baselineView, baselineScrape, baseline, baseline, faulted, Map.of("dropping", pg.isDropping()));

        // ---- FAULT: 고정한 3건만 SUCCESS 저장 뒤 응답 유실, callback 보류. 나머지 7건은 정상 경로 ----
        stage = "FAULT";
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
        Scrape faultScrape = awaitAwaitingGauges(0, 3);
        assertThat(faultScrape.awaiting()).as("미확인(거래 키 없음) 3, 접수 대기 0").containsExactly(entry("known", 0.0), entry("missing", 3.0));
        for (Long orderId : faulted) {
            Map<String, Object> order = db.orderPayment(orderId);
            assertThat(order.get("status")).isEqualTo("AWAITING_PAYMENT");
            assertThat(order.get("transaction_key")).as("응답을 잃어 거래 키를 모른다").isNull();
            assertThat(order.get("payment_attempt_key")).isEqualTo("attempt-" + orderId);
            assertThat(order.get("confirmed_at")).isNull();
            assertThat(ledgerTypes(orderId)).as("결제 대기 동안 예약 유지").containsExactly("RESERVE");
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
        Map<String, Object> faultExtra = new LinkedHashMap<>();
        faultExtra.put("dropTargetsWhileConfirming", faulted.stream().map(id -> "ORDER-" + id).toList());
        faultExtra.put("confirmResponses", faultConfirmResponses);
        writeStage("02-fault", faultView, faultScrape, faultSet, baseline, faulted, faultExtra);

        // ---- 증거 보존·진단: 읽기만 한다. 복구 API 는 아직 부르지 않는다 ----
        stage = "DIAGNOSIS";
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
        Map<Long, Map<String, Object>> afterDiagnosis = new LinkedHashMap<>();
        faulted.forEach(orderId -> afterDiagnosis.put(orderId, db.orderPayment(orderId)));
        assertThat(afterDiagnosis).as("진단 조회는 상태를 바꾸지 않는다").isEqualTo(beforeDiagnosis);
        assertThat(pg.lookups()).hasSize(lookupsBeforeDiagnosis + faulted.size());
        Scrape diagnosisScrape = awaitAwaitingGauges(0, 3);
        assertThat(diagnosisScrape.awaiting()).as("진단 뒤에도 지표 그대로").containsExactly(entry("known", 0.0), entry("missing", 3.0));

        int lookupsBeforeForeign = pg.lookups().size();
        // 기존 계약: 남의 주문은 ER-06 NOT_OWNER (403, 존재를 숨기지 않는다)
        client.post(resolvePath(faulted.get(0)), otherUser.getId(), null).assertError(HttpStatus.FORBIDDEN, "NOT_OWNER");
        assertThat(pg.lookups()).as("다른 사용자의 조정은 PG 를 부르지 않는다").hasSize(lookupsBeforeForeign);
        assertThat(db.orderPayment(faulted.get(0))).isEqualTo(beforeDiagnosis.get(faulted.get(0)));
        evidence.writeJson("03-diagnosis.json", diagnosisRecord(faulted, beforeDiagnosis, afterDiagnosis, diagnosis, verdicts, diagnosisScrape));

        // ---- RECOVERY: 응답 유실 OFF, 같은 거래·DB. 같은 3건에 기존 결제 대기 해소 API ----
        stage = "RECOVERY";
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
            assertThat(ledgerTypes(orderId)).as("성공 때 추가 차감·반환 없음").containsExactly("RESERVE");
            assertThat(db.outboxOfOrder(orderId)).as("확정 사실은 주문당 Outbox 한 행").hasSize(1);
        }
        assertThat(db.stockOf(product.getId())).isEqualTo(STOCK - 2 * SET_SIZE);
        assertThat(db.pointOf(user.getId())).as("카드 결제 복구는 포인트를 바꾸지 않는다").isEqualTo(BALANCE);
        Scrape recoveryScrape = awaitAwaitingGauges(0, 0);
        assertThat(recoveryScrape.awaiting()).containsExactly(entry("known", 0.0), entry("missing", 0.0));
        Map<String, Object> recoveryExtra = new LinkedHashMap<>();
        recoveryExtra.put("dropping", pg.isDropping());
        recoveryExtra.put("createsBeforeRecovery", createsBeforeRecovery);
        recoveryExtra.put("createsAfterRecovery", pg.createAttempts().size());
        recoveryExtra.put("duplicateCreates", pg.duplicateCreates().size());
        recoveryExtra.put("stockAvailable", db.stockOf(product.getId()));
        recoveryExtra.put("pointBalance", db.pointOf(user.getId()));
        writeStage("04-recovery", recoveryView, recoveryScrape, faultSet, baseline, faulted, recoveryExtra);

        // ---- 후속: 같은 주 20건 Outbox → 실제 relay·Kafka·consumer → 구매 활동 → 새 주간 snapshot → 랭킹 API ----
        stage = "DOWNSTREAM";
        List<Long> allOrders = new ArrayList<>(baseline);
        allOrders.addAll(faultSet);
        Map<Long, Object> confirmedAtBefore = new LinkedHashMap<>();
        allOrders.forEach(orderId -> confirmedAtBefore.put(orderId, db.orderPayment(orderId).get("confirmed_at")));
        Map<Long, Instant> firstConfirmedAt = new LinkedHashMap<>();
        baseline.forEach(orderId -> firstConfirmedAt.put(orderId, T_BASELINE));
        faultHealthy.forEach(orderId -> firstConfirmedAt.put(orderId, T_FAULT));
        faulted.forEach(orderId -> firstConfirmedAt.put(orderId, T_RECOVERY));

        API_CLOCK.set(T_RELAY);
        OrderEventRelay relay = new OrderEventRelay(api.getBean(OrderEventOutboxProcessor.class), api.getBean(OrderEventSender.class),
            api.getBean(OutboxRelayProperties.class), API_CLOCK);
        OrderEventRelay.Run firstRun = relay.runDue();
        assertThat(firstRun.published()).as("baseline 10 + 장애 집합 10").isEqualTo(20);
        assertThat(relay.runDue().published()).isZero();
        E2eKafka.awaitConsumedAll(GROUP, TOPIC);

        for (Long orderId : allOrders) {
            assertThat(db.activityWithTimeOfOrder(orderId)).as("주문 %d: 품목 하나·수량 1·최초 확정 시각(UTC)의 활동 한 행", orderId)
                .extracting(row -> row.get("product_id"), row -> row.get("quantity"), row -> row.get("confirmed_at"))
                .containsExactly(tuple(product.getId(), 1, utcText(firstConfirmedAt.get(orderId))));
            assertThat(db.processedOfOrder(orderId)).as("소비 완료 기록 한 번").hasSize(1);
            List<Map<String, Object>> outbox = db.outboxOfOrder(orderId);
            assertThat(outbox).hasSize(1);
            assertThat(outbox.get(0).get("status")).isEqualTo("PUBLISHED");
            assertThat(((Number) outbox.get(0).get("attempt_count")).intValue()).as("발행 시도 수(업무 활동 수와 별개)").isEqualTo(1);
            assertThat(db.orderPayment(orderId).get("confirmed_at")).as("후속 처리가 확정 시각을 덮어쓰지 않는다").isEqualTo(confirmedAtBefore.get(orderId));
        }
        assertThat(db.activityCount()).isEqualTo(20);

        WeeklyRankingTarget target = new WeeklyRankingTarget(WEEK, 1);
        JobExecution execution = batch.getBean(JobLauncher.class).run(batch.getBean(WeeklyRankingJobConfig.JOB_NAME, Job.class), target.toJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<List<Long>> expectedInputs = allOrders.stream().sorted().map(orderId -> List.of(orderId, product.getId(), 1L)).toList();
        assertThat(db.rankingInputs(target.snapshotId())).as("baseline 을 빼지 않은 그 주의 전체 입력 20줄").isEqualTo(expectedInputs);
        List<RankingChecksum.InputLine> lines = allOrders.stream().sorted()
            .map(orderId -> new RankingChecksum.InputLine(orderId, product.getId(), 1, LocalDateTime.ofInstant(firstConfirmedAt.get(orderId), ZoneOffset.UTC)))
            .toList();
        Map<String, Object> snapshot = db.rankingSnapshot(target.snapshotId());
        assertThat(snapshot.get("input_checksum")).isEqualTo(RankingChecksum.ofInputs(lines));
        List<List<Long>> expectedRanking = List.of(List.of(product.getId(), 20L, 1L));
        assertThat(db.ranking(target.snapshotId())).isEqualTo(expectedRanking);
        JsonNode rankingApi = client.get("/api/v1/rankings/weekly?periodStart=" + WEEK, null).assertSuccess(HttpStatus.OK).data();
        assertThat(rankingApi.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(rankingApi.path("snapshotId").asText()).isEqualTo(target.snapshotId());
        assertThat(rankingApi.path("revision").asLong()).isEqualTo(1);
        assertThat(rankingItems(rankingApi)).isEqualTo(expectedRanking);
        evidence.writeJson("05-downstream.json", downstreamRecord(allOrders, faulted, firstRun, execution, target, snapshot, rankingApi));

        // ---- 증거 마무리: fault 기록이 덮어써지지 않았는지, 파일 내용이 같은 ID·상태를 담는지 ----
        stage = "EVIDENCE";
        JsonNode faultFile = evidence.readJson("02-fault.json");
        assertThat(StreamSupport.stream(faultFile.path("faulted").spliterator(), false).map(JsonNode::asLong).toList()).isEqualTo(faulted);
        for (JsonNode order : faultFile.path("orders")) {
            String expectedStatus = faulted.contains(order.path("orderId").asLong()) ? "AWAITING_PAYMENT" : "CONFIRMED";
            assertThat(order.path("status").asText()).as("fault 기록의 주문 %s", order.path("orderId")).isEqualTo(expectedStatus);
        }
        assertThat(evidence.currentHash("02-fault.json")).as("fault 기록을 덮어쓰지 않았다").isEqualTo(evidence.recordedHash("02-fault.json"));
        evidence.writeJsonLines("boundary-logs.jsonl", boundaryLogRecords());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("result", "PASSED");
        manifest.put("faultFile", Map.of("name", "02-fault.json", "sha256AtWrite", evidence.recordedHash("02-fault.json"),
            "sha256AtEnd", evidence.currentHash("02-fault.json"), "preserved", true));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("baseline", baseline);
        summary.put("faultSet", faultSet);
        summary.put("faulted", faulted);
        summary.put("verdicts", verdicts);
        summary.put("pgCreates", pg.createAttempts().size());
        summary.put("pgLookups", pg.lookups().size());
        summary.put("pgCallbacks", pg.callbacks().size());
        summary.put("rankingSnapshot", target.snapshotId());
        summary.put("ranking", expectedRanking);
        manifest.put("summary", summary);
        evidence.writeManifest(manifest);

        LoggerFactory.getLogger(CheckoutIncidentTest.class).info(
            "[W10 관찰] evidence={}, baseline={}, faultSet={}, faulted={}, verdicts={}, views={}, createsTotal={}, lookupsTotal={}, callbacksTotal={}, ranking={}",
            evidence.dir(), baseline, faultSet, faulted, verdicts, List.of(baselineView, faultView, recoveryView),
            pg.createAttempts().size(), pg.lookups().size(), pg.callbacks().size(), expectedRanking);
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

    private List<Object> ledgerTypes(Long orderId) {
        return db.ledgerOfOrder(orderId).stream().map(row -> row.get("type")).toList();
    }

    private StageView view(String stageName, List<Long> orderIds) throws IOException, InterruptedException {
        long confirmed = orderIds.stream().filter(id -> "CONFIRMED".equals(db.orderPayment(id).get("status"))).count();
        long awaiting = orderIds.stream().filter(id -> "AWAITING_PAYMENT".equals(db.orderPayment(id).get("status"))).count();
        long pgSuccess = orderIds.stream()
            .filter(id -> pg.transaction(id).map(transaction -> transaction.status() == FakePgServer.Status.SUCCESS).orElse(false)).count();
        return new StageView(stageName, Instant.now(), confirmed, awaiting, pgSuccess, pg.transactions().size(),
            JSON.readTree(get("/actuator/health").body()).path("status").asText());
    }

    private static String utcText(Instant instant) {
        return DB_TEXT.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /** (productId, score, rank) 순서대로. */
    private static List<List<Long>> rankingItems(JsonNode data) {
        return StreamSupport.stream(data.path("items").spliterator(), false)
            .map(item -> List.of(item.path("product").path("id").asLong(), item.path("score").asLong(), item.path("rank").asLong()))
            .toList();
    }

    // ---- 진단 ----

    /**
     * 경쟁 가설 판정. 조회 결과만이 아니라 실제 경계 기록(요청 결과 로그·PgClient 응답 처리 로그·반영 TX 결말)까지 맞아야 응답 유실이다. 부족하면 UNDETERMINED.
     * PG_DELAYED: 권위 조회가 PENDING. APPLY_FAILED: 반영 TX 가 ROLLED_BACK. RESPONSE_LOST: 요청 결과 UNKNOWN + 빈 본문 기록 + 권위 조회 SUCCESS + rollback 없음.
     */
    private Verdict judge(Long orderId, PaymentGatewayResult lookup) {
        Map<String, Boolean> facts = verdictFacts(orderId, lookup);
        if (facts.get("applyRolledBack")) {
            return Verdict.APPLY_FAILED;
        }
        if (facts.get("lookupPending")) {
            return Verdict.PG_DELAYED;
        }
        if (facts.get("requestOutcomeUnknown") && facts.get("pgClientEmptyBody") && facts.get("lookupSuccess")) {
            return Verdict.RESPONSE_LOST;
        }
        return Verdict.UNDETERMINED;
    }

    private Map<String, Boolean> verdictFacts(Long orderId, PaymentGatewayResult lookup) {
        List<Map<String, Object>> facade = keyValues(orderId);
        Map<String, Boolean> facts = new LinkedHashMap<>();
        facts.put("requestOutcomeUnknown", facade.stream()
            .anyMatch(kv -> "payment.request.result".equals(kv.get("event")) && "UNKNOWN".equals(kv.get("outcome"))));
        facts.put("pgClientEmptyBody", boundaryLogs.list.stream()
            .anyMatch(event -> event.getLoggerName().equals(PgClient.class.getName())
                && event.getFormattedMessage().startsWith("PG 응답 본문 없음")
                && event.getFormattedMessage().contains("[orderId = " + orderId + "]")));
        facts.put("applyRolledBack", facade.stream()
            .anyMatch(kv -> "payment.apply".equals(kv.get("event")) && "ROLLED_BACK".equals(kv.get("tx"))));
        facts.put("lookupPending", lookup.outcome() == Outcome.PENDING);
        facts.put("lookupSuccess", lookup.outcome() == Outcome.SUCCESS);
        return facts;
    }

    /** OrderFacade 경계 로그 중 이 주문의 key-value, 남긴 순서대로. */
    private List<Map<String, Object>> keyValues(Long orderId) {
        return boundaryLogs.list.stream()
            .filter(event -> event.getLoggerName().equals(OrderFacade.class.getName()) && event.getKeyValuePairs() != null)
            .map(CheckoutIncidentTest::keyValuesOf)
            .filter(keyValues -> orderId.equals(keyValues.get("orderId")))
            .toList();
    }

    private static Map<String, Object> keyValuesOf(ILoggingEvent event) {
        Map<String, Object> keyValues = new LinkedHashMap<>();
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                keyValues.put(pair.key, pair.value);
            }
        }
        return keyValues;
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

    // ---- 증거 기록 (합성 식별자만. 카드번호는 쓰지 않는다) ----

    private Map<String, Object> runRecord(int apiPort, ProductModel product, List<Long> baseline, List<Long> faultSet, List<Long> faulted) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", evidence.runId());
        run.put("startedAt", Instant.now().toString());
        run.put("scope", "로컬 통제 실험: Testcontainers MySQL·Redis·Kafka, HTTP 가짜 PG. 실제 카드·계정·외부 PG·운영 DB 없음");
        Map<String, Object> clock = new LinkedHashMap<>();
        clock.put("week", WEEK.toString());
        clock.put("baseline", T_BASELINE.toString());
        clock.put("fault", T_FAULT.toString());
        clock.put("recovery", T_RECOVERY.toString());
        clock.put("relay", T_RELAY.toString());
        clock.put("batch", BATCH_CLOCK.instant().toString());
        run.put("clock", clock);
        run.put("ports", Map.of("api", apiPort, "management", managementPort, "fakePg", URI.create(pg.baseUrl()).getPort()));
        Map<String, Object> fault = new LinkedHashMap<>();
        fault.put("kind", "PG 가 SUCCESS 거래를 저장한 뒤 생성 응답을 200 + 빈 본문으로 보낸다. callback 은 보내지 않는다");
        fault.put("positionsInFaultSet", FAULT_POSITIONS);
        fault.put("autoLookupDelay", api.getEnvironment().getProperty("pg.lookup-delay"));
        fault.put("paymentExpiryInterval", api.getEnvironment().getProperty("pg.payment-expiry-interval"));
        run.put("fault", fault);
        run.put("metric", Map.of("name", "commerce_payment_awaiting", "tags", List.of("pg_transaction=known", "pg_transaction=missing"),
            "refreshInterval", api.getEnvironment().getProperty("commerce.metrics.payment-awaiting.refresh-interval")));
        run.put("userId", user.getId());
        run.put("productId", product.getId());
        run.put("stockAtStart", STOCK);
        run.put("baseline", baseline);
        run.put("faultSet", faultSet);
        run.put("faulted", faulted);
        return run;
    }

    private void writeStage(String name, StageView view, Scrape scrape, List<Long> orderIds, List<Long> baseline, List<Long> faulted,
                            Map<String, Object> extra) throws IOException, InterruptedException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("stage", view.stage());
        record.put("observedAt", view.observedAt().toString());
        record.put("view", Map.of("confirmed", view.confirmed(), "awaiting", view.awaiting(), "pgSuccess", view.pgSuccess(),
            "cumulativePgTransactions", view.cumulativePgTransactions()));
        record.put("health", JSON.readTree(get("/actuator/health").body()));
        record.put("metricFromPrometheusFile", Map.of("file", name + ".prom", "commerce_payment_awaiting", scrape.awaiting()));
        record.put("faulted", faulted);
        record.put("orders", orderIds.stream().map(orderId -> orderRecord(orderId, baseline.contains(orderId) ? "baseline" : "fault", faulted)).toList());
        record.putAll(extra);
        evidence.writeText(name + ".prom", scrape.raw());
        evidence.writeJson(name + ".json", record);
    }

    private Map<String, Object> orderRecord(Long orderId, String set, List<Long> faulted) {
        Map<String, Object> order = new LinkedHashMap<>();
        Map<String, Object> row = db.orderPayment(orderId);
        order.put("orderId", orderId);
        order.put("set", set);
        order.put("faulted", faulted.contains(orderId));
        order.put("status", row.get("status"));
        order.put("paymentAttemptKey", row.get("payment_attempt_key"));
        order.put("transactionKey", row.get("transaction_key"));
        order.put("totalAmount", row.get("total_amount"));
        order.put("paidAmount", row.get("paid_amount"));
        order.put("paymentStartedAt", row.get("payment_started_at"));
        order.put("confirmedAt", row.get("confirmed_at"));
        order.put("ledger", ledgerTypes(orderId));
        order.put("outbox", db.outboxOfOrder(orderId).stream().map(outbox -> Map.of("eventId", outbox.get("event_id"), "status", outbox.get("status"))).toList());
        order.put("pg", pg.transaction(orderId).map(CheckoutIncidentTest::transactionRecord).orElse(null));
        order.put("pgCreateCount", pg.createCount(orderId));
        return order;
    }

    private static Map<String, Object> transactionRecord(FakePgServer.Transaction transaction) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("transactionKey", transaction.transactionKey());
        record.put("userId", transaction.userId());
        record.put("pgOrderId", transaction.pgOrderId());
        record.put("amount", transaction.amount());
        record.put("status", transaction.status().name());
        record.put("createdAt", transaction.createdAt().toString());
        record.put("updatedAt", transaction.updatedAt().toString());
        return record;
    }

    private Map<String, Object> diagnosisRecord(List<Long> faulted, Map<Long, Map<String, Object>> before, Map<Long, Map<String, Object>> after,
                                                Map<Long, PaymentGatewayResult> diagnosis, Map<Long, Verdict> verdicts, Scrape scrape) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("stage", "DIAGNOSIS");
        record.put("observedAt", Instant.now().toString());
        record.put("hypotheses", List.of(
            Map.of("id", "PG_DELAYED", "claim", "PG 처리 자체가 지연", "expected", "권위 조회 PENDING", "check", "해당 거래 조회"),
            Map.of("id", "RESPONSE_LOST", "claim", "PG 성공 응답 유실",
                "expected", "PG SUCCESS·내부 결제 대기(거래 키 없음)·요청 결과 UNKNOWN·PgClient 본문 없음 기록·rollback 없음",
                "check", "거래 조회·payment.request.result·PgClient 로그·payment.apply"),
            Map.of("id", "APPLY_FAILED", "claim", "성공 결과의 내부 반영 실패", "expected", "PG SUCCESS·payment.apply ROLLED_BACK·예약/Outbox 그대로",
                "check", "payment.apply tx·예약·Outbox")));
        record.put("rule", "PG SUCCESS 와 결제 대기의 조합만으로 판정하지 않는다. 경계 기록이 모두 맞지 않으면 UNDETERMINED");
        record.put("readOnlyLookup", "api 의 PaymentGateway.find 만 직접 불렀다 (결과를 적용하지 않음)");
        List<Map<String, Object>> orders = new ArrayList<>();
        for (Long orderId : faulted) {
            PaymentGatewayResult lookup = diagnosis.get(orderId);
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("orderId", orderId);
            order.put("lookup", Map.of("outcome", lookup.outcome().name(), "transactionKey", String.valueOf(lookup.transactionKey()),
                "amount", String.valueOf(lookup.amount())));
            order.put("facts", verdictFacts(orderId, lookup));
            order.put("boundaryLogs", keyValues(orderId));
            order.put("verdict", verdicts.get(orderId).name());
            order.put("stateBefore", before.get(orderId));
            order.put("stateAfter", after.get(orderId));
            order.put("stateUnchanged", before.get(orderId).equals(after.get(orderId)));
            orders.add(order);
        }
        record.put("orders", orders);
        record.put("metricFromPrometheus", scrape.awaiting());
        record.put("foreignUserResolve", "403 NOT_OWNER, PG 조회 증가 0, 주문 상태 그대로");
        return record;
    }

    private Map<String, Object> downstreamRecord(List<Long> allOrders, List<Long> faulted, OrderEventRelay.Run run, JobExecution execution,
                                                 WeeklyRankingTarget target, Map<String, Object> snapshot, JsonNode rankingApi) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("stage", "DOWNSTREAM");
        record.put("relay", Map.of("claimed", run.claimed(), "published", run.published(), "failed", run.failed(), "blocked", run.blocked()));
        List<Map<String, Object>> orders = new ArrayList<>();
        for (Long orderId : allOrders) {
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("orderId", orderId);
            order.put("faulted", faulted.contains(orderId));
            order.put("confirmedAt", db.orderPayment(orderId).get("confirmed_at"));
            order.put("outbox", db.outboxOfOrder(orderId).stream().map(outbox -> Map.of("eventId", outbox.get("event_id"), "status", outbox.get("status"),
                "attemptCount", outbox.get("attempt_count"))).toList());
            order.put("activity", db.activityWithTimeOfOrder(orderId));
            order.put("processed", db.processedOfOrder(orderId).size());
            orders.add(order);
        }
        record.put("orders", orders);
        record.put("activityCount", db.activityCount());
        record.put("job", Map.of("executionId", execution.getId(), "status", execution.getStatus().name(), "snapshotId", target.snapshotId()));
        Map<String, Object> snapshotRecord = new LinkedHashMap<>();
        snapshot.forEach((key, value) -> snapshotRecord.put(key, value == null ? null : value.toString()));
        record.put("snapshot", snapshotRecord);
        record.put("rankingInputs", db.rankingInputs(target.snapshotId()));
        record.put("ranking", db.ranking(target.snapshotId()));
        record.put("rankingApi", rankingApi);
        return record;
    }

    private List<Map<String, Object>> boundaryLogRecords() {
        return boundaryLogs.list.stream().map(event -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("time", Instant.ofEpochMilli(event.getTimeStamp()).toString());
            line.put("logger", event.getLoggerName());
            line.put("level", event.getLevel().toString());
            line.put("message", event.getFormattedMessage());
            line.put("kv", keyValuesOf(event));
            return line;
        }).toList();
    }

    /** 중간 실패 기록. 원래 실패를 가리지 않도록 여기서 난 예외는 삼킨다. 거래·기록은 초기화하지 않는다. */
    private void writeFailure(Throwable failure) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("result", "FAILED");
            record.put("stage", stage);
            record.put("failedAt", Instant.now().toString());
            record.put("type", failure.getClass().getName());
            record.put("message", String.valueOf(failure.getMessage()));
            if (pg != null) {
                record.put("pg", Map.of("dropping", pg.isDropping(), "transactions", pg.transactions().size(), "creates", pg.createAttempts().size()));
            }
            evidence.writeJson("failure.json", record);
            evidence.writeJsonLines("boundary-logs.jsonl", boundaryLogRecords());
        } catch (RuntimeException ignored) {
            // 실패 기록 실패가 원래 실패를 덮지 않게
        }
    }

    // ---- 관리 endpoint (실제 주입된 management 포트) ----

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path)).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** 한 번의 Prometheus 원문과 거기서 읽은 commerce_payment_awaiting 의 pg_transaction 별 값 (known·missing 순). 줄이 없으면 그 태그는 빠진다. */
    private Scrape scrape() throws IOException, InterruptedException {
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
        return new Scrape(response.body(), values);
    }

    /**
     * 주기 갱신 gauge 라 늦을 수 있다: METRIC_WAIT 안에 같은 scrape 의 known·missing 이 기대값이 되면 그 scrape 를 돌려준다.
     * 한 태그만 기다리면 다른 태그가 중간 시점 값일 수 있어 둘을 함께 본다. 시간 안에 안 되면 마지막 scrape 를 돌려줘 호출부 assert 가 실패한다.
     */
    private Scrape awaitAwaitingGauges(double known, double missing) throws IOException, InterruptedException {
        Map<String, Double> expected = Map.of("known", known, "missing", missing);
        long deadline = System.nanoTime() + METRIC_WAIT.toNanos();
        Scrape last = scrape();
        while (!last.awaiting().equals(expected) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            last = scrape();
        }
        return last;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
