package com.loopers.interfaces.api.order;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.coupon.entity.Coupon;
import com.loopers.domain.coupon.entity.CouponCampaign;
import com.loopers.domain.coupon.enums.DiscountType;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.enums.ProviderTransactionStatus;
import com.loopers.domain.payment.gateway.PaymentGateway;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.domain.payment.vo.ProviderTransaction;
import com.loopers.domain.point.entity.Point;
import com.loopers.domain.product.entity.Product;
import com.loopers.domain.user.entity.User;
import com.loopers.infrastructure.payment.client.TestPgPaymentGateway;
import com.loopers.support.incident.IncidentEvidence;
import com.loopers.support.pg.TestPgServer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 10주차 사고 실험 (설계 D-206). PG 는 SUCCESS 인데 생성 응답을 잃어 내부가 UNKNOWN 으로 남는 사고를
 * 같은 DB · 같은 테스트 PG 상태로 baseline → fault → 증거 보존 → 진단 → recovery 순서로 잇는다. 단계 사이에 DB · PG 를 초기화하지 않음
 */
@AutoConfigureObservability(tracing = false)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "payment.metrics.unresolved.refresh-ms=200"}
)
@AutoConfigureMockMvc
class CheckoutIncidentTest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final int ORDERS_PER_SET = 10;
    private static final int INITIAL_STOCK = 30;
    private static final long PRICE = 1_000L;
    private static final long INITIAL_POINT = 5_000L;
    private static final Duration GAUGE_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern UNRESOLVED_LINE = Pattern.compile("commerce_payment_unresolved\\{([^}]*)} (\\S+)");
    private static final Path EVIDENCE_ROOT = Path.of("build", "incident-evidence");
    private static final LocalDateTime WEEK_START = LocalDateTime.parse("2026-08-17T00:00:00");

    /** 모든 주문이 2026-08-17 주에 처음 확정되도록 고정함 (D-207) */
    private static final IncidentClock CLOCK = new IncidentClock(Instant.parse("2026-08-19T12:00:00Z"));

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private PaymentGateway paymentGateway;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @LocalManagementPort
    private int managementPort;

    private final TestPgServer pg = TestPgServer.shared();
    private final HttpClient http = HttpClient.newHttpClient();
    private final Logger gatewayLogger = (Logger) LoggerFactory.getLogger(TestPgPaymentGateway.class);
    private final Logger facadeLogger = (Logger) LoggerFactory.getLogger(OrderPaymentFacade.class);
    private final ListAppender<ILoggingEvent> paymentLogs = new ListAppender<>();
    private User user;
    private User otherUser;
    private Product product;

    @BeforeEach
    void setUp() {
        pg.reset();
        CLOCK.set(Instant.parse("2026-08-19T12:00:00Z"));
        paymentLogs.start();
        gatewayLogger.addAppender(paymentLogs);
        facadeLogger.addAppender(paymentLogs);
        transactionTemplate.executeWithoutResult(status -> {
            user = new User();
            entityManager.persist(user);
            otherUser = new User();
            entityManager.persist(otherUser);
            Brand brand = new Brand("사고 실험 브랜드", null);
            entityManager.persist(brand);
            product = new Product(brand, "사고 실험 상품", PRICE);
            product.changeStock(INITIAL_STOCK);
            entityManager.persist(product);
            // PG 복구가 건드리지 않아야 하는 기존 포인트 · 쿠폰
            Point point = new Point(user.getId());
            point.charge(INITIAL_POINT);
            entityManager.persist(point);
            CouponCampaign campaign = new CouponCampaign("사고 실험 쿠폰", 10, DiscountType.FIXED_AMOUNT, 500L, "PG 복구 불변 확인용");
            campaign.open();
            entityManager.persist(campaign);
            entityManager.persist(Coupon.issue(campaign, user.getId(), 1, ZonedDateTime.now(CLOCK)));
        });
    }

    @AfterEach
    void tearDown() {
        // 테스트가 켠 주입만 풂. 거래 · 증거 파일은 지우지 않음
        pg.stopDroppingResponses();
        gatewayLogger.detachAppender(paymentLogs);
        facadeLogger.detachAppender(paymentLogs);
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("PG 성공 응답을 잃은 3건만 UNKNOWN 으로 남고, 증거로 응답 유실을 가린 뒤 같은 주문을 create 추가 없이 복구한다.")
    @Test
    void diagnosesAndRecoversLostSuccessResponse() throws Exception {
        IncidentEvidence evidence = IncidentEvidence.start(EVIDENCE_ROOT, objectMapper);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", evidence.runId());
        run.put("startedAt", Instant.now().toString());
        run.put("clockStart", CLOCK.instant().toString());
        run.put("result", "FAILED");
        String stage = "baseline";
        try {
            // baseline: 응답 유실 없이 정상 10건
            assertThat(pg.dropTargets()).as("baseline 에서는 응답 유실이 꺼져 있어야 함").isEmpty();
            List<Long> baseline = createOrders();
            for (Long orderId : baseline) {
                assertThat(confirm(orderId).at("/data/paymentStatus").asText()).isEqualTo("PENDING");
                completeAtPg(orderId);
                reconcile(orderId);
            }

            Map<Long, OrderState> baselineStates = states(baseline);
            assertAll(
                () -> assertThat(baselineStates.values()).allSatisfy(state -> assertThat(state.confirmed()).isTrue()),
                () -> assertThat(baseline).allSatisfy(this::assertSingleSuccessTransaction),
                () -> assertThat(pg.transactions()).hasSize(10),
                () -> assertThat(pg.createCount()).isEqualTo(10),
                () -> assertThat(stock()).isEqualTo(INITIAL_STOCK - 10),
                () -> assertThat(healthStatus()).isEqualTo("UP"),
                () -> assertThat(awaitUnresolved("UNKNOWN", 0)).isZero()
            );
            evidence.writeJson("baseline.json", stageRecord("baseline", baseline, Map.of()));
            CLOCK.advance(Duration.ofMinutes(5));

            // fault: 별도 집합 10건 중 실제 ID 로 고른 3건만 거래 저장 뒤 응답 유실 (D-198)
            stage = "fault";
            List<Long> fault = createOrders();
            List<Long> dropped = fault.subList(0, 3);
            List<Long> normal = fault.subList(3, ORDERS_PER_SET);
            Set<String> droppedProviderOrderIds = dropped.stream().map(Payment::providerOrderIdOf).collect(Collectors.toSet());
            pg.willDropResponseFor(droppedProviderOrderIds);
            try {
                for (Long orderId : fault) {
                    String expected = dropped.contains(orderId) ? "UNKNOWN" : "PENDING";
                    assertThat(confirm(orderId).at("/data/paymentStatus").asText()).isEqualTo(expected);
                }
            } finally {
                // 새 실험 입력을 멈춘 뒤에는 유실을 끔
                pg.stopDroppingResponses();
            }
            int lookupsBeforeSettle = pg.lookupCount();
            for (Long orderId : normal) {
                completeAtPg(orderId);
                reconcile(orderId);
            }
            // 장애 3건은 PG 에 SUCCESS 만 저장하고 내부 조회는 보류함. callback 을 보내는 경로가 없어 끌 것도 없음
            dropped.forEach(this::completeAtPg);

            Map<Long, OrderState> faultStates = states(fault);
            assertAll(
                () -> assertThat(normal).allSatisfy(orderId -> assertThat(faultStates.get(orderId).confirmed()).isTrue()),
                () -> assertThat(dropped).allSatisfy(orderId -> assertThat(faultStates.get(orderId))
                    .isEqualTo(new OrderState(orderId, "PAYMENT_PENDING", "UNKNOWN", "RESERVED", null))),
                () -> assertThat(fault).allSatisfy(this::assertSingleSuccessTransaction),
                () -> assertThat(pg.transactions()).hasSize(20),
                () -> assertThat(pg.createCount()).isEqualTo(20),
                () -> assertThat(pg.duplicateCreates()).isEmpty(),
                // UNKNOWN 3건도 예약을 유지함
                () -> assertThat(stock()).isEqualTo(INITIAL_STOCK - 20),
                () -> assertThat(pg.lookupCount() - lookupsBeforeSettle).as("조회는 정상 7건의 reconcile 뿐").isEqualTo(normal.size()),
                () -> assertThat(healthStatus()).isEqualTo("UP"),
                () -> assertThat(awaitUnresolved("UNKNOWN", 3)).isEqualTo(3),
                () -> assertThat(awaitUnresolved("PENDING", 0)).isZero(),
                () -> assertThat(unknownCreateLogs()).containsExactlyInAnyOrderElementsOf(droppedProviderOrderIds)
            );

            // 증거 보존: 복구 API 를 부르기 전에 fault 원문을 남기고 hash 를 잡아 둠
            stage = "preserve";
            Map<String, Object> faultExtra = new LinkedHashMap<>();
            faultExtra.put("droppedOrderIds", dropped);
            // 진단 조회도 fixture 에 조회로 남으므로 그 전에 셈
            Map<String, Integer> lookupsDuringFault = lookupsByOrder(dropped);
            faultExtra.put("lookupsDuringFaultByOrder", lookupsByOrder(fault));
            evidence.writeJson("fault.json", stageRecord("fault", fault, faultExtra));
            String faultHash = evidence.sha256("fault.json");

            // 진단: 읽기 전용 조회로 PG 쪽 사실을 보고, 그동안 commerce 상태가 바뀌지 않았는지 확인함 (D-204)
            stage = "diagnosis";
            Map<String, Object> beforeLookup = commerceFingerprint();
            Map<Long, PaymentLookupResult> lookups = new LinkedHashMap<>();
            for (Long orderId : dropped) {
                lookups.put(orderId, paymentGateway.findByProviderOrderId(Payment.providerOrderIdOf(orderId)));
            }
            Map<String, Object> afterLookup = commerceFingerprint();
            Map<String, Object> diagnosis = diagnose(dropped, lookups, lookupsDuringFault);
            diagnosis.put("commerceUnchangedByLookup", beforeLookup.equals(afterLookup));
            evidence.writeJson("diagnosis.json", diagnosis);
            assertAll(
                () -> assertThat(afterLookup).as("진단 조회는 commerce 상태를 바꾸지 않음").isEqualTo(beforeLookup),
                () -> assertThat(verdict(diagnosis, "H1")).isEqualTo("REFUTED"),
                () -> assertThat(verdict(diagnosis, "H2")).isEqualTo("SUPPORTED"),
                () -> assertThat(verdict(diagnosis, "H3")).isEqualTo("REFUTED")
            );

            // recovery: 검증한 SUCCESS 만 본인 reconcile 로 복구함. create 는 다시 보내지 않음 (D-208)
            stage = "recovery";
            for (Long orderId : dropped) {
                assertRecoverable(orderId, lookups.get(orderId));
            }
            CLOCK.advance(Duration.ofMinutes(5));
            ZonedDateTime recoveredAt = ZonedDateTime.now(CLOCK);
            int createsBeforeRecovery = pg.createCount();
            for (Long orderId : dropped) {
                assertThat(reconcile(orderId).at("/data/status").asText()).isEqualTo("CONFIRMED");
            }
            // 반복 reconcile · 본문만 조작한 callback 은 같은 사실로 수렴해야 함
            int outboxAfterRecovery = outboxCount();
            for (int i = 0; i < 2; i++) {
                for (Long orderId : dropped) {
                    reconcile(orderId);
                    forgedCallback(orderId);
                }
            }
            int otherUserReconcile = postStatus("/api/v1/orders/" + dropped.get(0) + "/payment/reconcile", otherUser);

            Map<Long, OrderState> recoveredStates = states(fault);
            assertAll(
                () -> assertThat(recoveredStates.values()).allSatisfy(state -> assertThat(state.confirmed()).isTrue()),
                () -> assertThat(fault).allSatisfy(orderId -> assertThat(recoveredStates.get(orderId).transactionKey())
                    .isEqualTo(pg.transactionsOf(Payment.providerOrderIdOf(orderId)).get(0).transactionKey())),
                () -> assertThat(fault).allSatisfy(this::assertSingleSuccessTransaction),
                () -> assertThat(pg.createCount() - createsBeforeRecovery).as("복구 중 create").isZero(),
                () -> assertThat(pg.transactions()).hasSize(20),
                () -> assertThat(pg.duplicateCreates()).isEmpty(),
                () -> assertThat(stock()).as("성공 적용 때 추가 차감 없음").isEqualTo(INITIAL_STOCK - 20),
                () -> assertThat(pointBalance()).isEqualTo(INITIAL_POINT),
                () -> assertThat(couponCount()).isEqualTo(1),
                () -> assertThat(outboxCountByOrder(fault)).as("장애 집합 주문마다 Outbox 1행")
                    .isEqualTo(fault.stream().collect(Collectors.toMap(id -> id, id -> 1))),
                () -> assertThat(outboxCount()).as("반복 호출로 Outbox 가 늘지 않음").isEqualTo(outboxAfterRecovery),
                () -> assertThat(otherUserReconcile).isEqualTo(404),
                () -> assertThat(confirmedAt(dropped)).allSatisfy(at -> assertThat(at)
                    .isEqualTo(recoveredAt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())
                    .isAfterOrEqualTo(WEEK_START).isBefore(WEEK_START.plusDays(7))),
                () -> assertThat(healthStatus()).isEqualTo("UP"),
                () -> assertThat(awaitUnresolved("UNKNOWN", 0)).isZero()
            );
            Map<String, Object> recoveryExtra = new LinkedHashMap<>();
            recoveryExtra.put("recoveredOrderIds", dropped);
            recoveryExtra.put("createsDuringRecovery", pg.createCount() - createsBeforeRecovery);
            recoveryExtra.put("otherUserReconcileStatus", otherUserReconcile);
            recoveryExtra.put("pointBalance", pointBalance());
            recoveryExtra.put("couponCount", couponCount());
            recoveryExtra.put("outboxByOrder", outboxCountByOrder(fault));
            recoveryExtra.put("confirmedAt", confirmedAt(dropped).stream().map(LocalDateTime::toString).toList());
            evidence.writeJson("recovery.json", stageRecord("recovery", fault, recoveryExtra));

            stage = "evidence";
            assertThat(evidence.sha256("fault.json")).as("fault 기록을 덮어쓰지 않음").isEqualTo(faultHash);
            run.put("faultSha256", faultHash);
            run.put("result", "PASSED");
        } finally {
            run.put("lastStage", stage);
            run.put("finishedAt", Instant.now().toString());
            evidence.writeLines("logs.txt", paymentLogs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            evidence.writeJson("run.json", run);
            evidence.writeChecksums();
        }
    }

    /** 내부 상태 한 줄. 거래 키는 생성 응답이나 조회로 알게 됐을 때만 있음 */
    private record OrderState(Long orderId, String orderStatus, String paymentStatus, String reservationStatus, String transactionKey) {
        boolean confirmed() {
            return "CONFIRMED".equals(orderStatus) && "SUCCESS".equals(paymentStatus)
                && "CONSUMED".equals(reservationStatus) && transactionKey != null;
        }
    }

    /** runbook 의 중단 조건과 같음: 거래 1건 · SUCCESS · 같은 주문 · 같은 금액이 아니면 복구하지 않음 */
    private void assertRecoverable(Long orderId, PaymentLookupResult lookup) {
        assertThat(lookup).as("주문 %d 진단 조회", orderId).isInstanceOf(PaymentLookupResult.Found.class);
        List<ProviderTransaction> transactions = ((PaymentLookupResult.Found) lookup).transactions();
        assertThat(transactions).as("주문 %d 거래", orderId).singleElement().satisfies(transaction -> {
            assertThat(transaction.status()).isEqualTo(ProviderTransactionStatus.SUCCESS);
            assertThat(transaction.providerOrderId()).isEqualTo(Payment.providerOrderIdOf(orderId));
            assertThat(transaction.amount()).isEqualTo(PRICE);
        });
    }

    /** 가설마다 예상 · 실제 · 판정. 판정은 주입 코드가 아니라 관찰로 내림 */
    private Map<String, Object> diagnose(
        List<Long> dropped, Map<Long, PaymentLookupResult> lookups, Map<String, Integer> lookupsDuringFault
    ) {
        List<String> providerOrderIds = dropped.stream().map(Payment::providerOrderIdOf).toList();
        List<String> lookupStatuses = lookups.values().stream()
            .flatMap(result -> result instanceof PaymentLookupResult.Found found
                ? found.transactions().stream().map(tx -> tx.status().name())
                : java.util.stream.Stream.of("UNAVAILABLE"))
            .toList();
        boolean allSingleSuccess = lookups.values().stream().allMatch(result -> result instanceof PaymentLookupResult.Found found
            && found.transactions().size() == 1 && found.transactions().get(0).status() == ProviderTransactionStatus.SUCCESS);
        boolean allPending = !lookupStatuses.isEmpty() && lookupStatuses.stream().allMatch("PENDING"::equals);
        boolean unknownLogged = unknownCreateLogs().containsAll(providerOrderIds);
        boolean internalUnknown = states(dropped).values().stream().allMatch(state -> "UNKNOWN".equals(state.paymentStatus()));
        List<String> applyFailures = logLines("결제 결과 적용 실패.", providerOrderIds);
        List<String> lookupApplies = logLines("결제 결과 적용.", providerOrderIds).stream().filter(line -> line.contains("source=LOOKUP")).toList();
        boolean neverLookedUp = lookupsDuringFault.values().stream().allMatch(count -> count == 0);

        List<Map<String, Object>> hypotheses = List.of(
            hypothesis("H1", "PG 처리 자체가 지연",
                "진단 조회의 거래 상태가 PENDING",
                Map.of("lookupStatuses", lookupStatuses),
                allPending ? "SUPPORTED" : allSingleSuccess ? "REFUTED" : "UNDETERMINED"),
            hypothesis("H2", "PG 성공 응답 유실",
                "거래 1건 SUCCESS, 내부 UNKNOWN, PG 생성 결과 result=UNKNOWN 로그",
                Map.of("allSingleSuccess", allSingleSuccess, "internalUnknown", internalUnknown, "unknownCreateLogged", unknownLogged),
                allSingleSuccess && internalUnknown && unknownLogged ? "SUPPORTED" : "UNDETERMINED"),
            hypothesis("H3", "성공 결과의 내부 반영 실패",
                "조회는 있었고 결제 결과 적용 실패 로그(TX rollback)가 남음",
                Map.of("applyFailureLogs", applyFailures, "lookupApplyLogs", lookupApplies, "lookupsDuringFault", lookupsDuringFault),
                // 조회 · 적용 시도가 없었다는 것과 실패 로그가 없다는 것을 함께 볼 때만 반박함
                neverLookedUp && applyFailures.isEmpty() && lookupApplies.isEmpty() ? "REFUTED" : "UNDETERMINED")
        );
        Map<String, Object> diagnosis = new LinkedHashMap<>();
        diagnosis.put("observedAt", CLOCK.instant().toString());
        diagnosis.put("targetOrderIds", dropped);
        diagnosis.put("lookups", lookups.entrySet().stream().collect(Collectors.toMap(
            entry -> entry.getKey().toString(), entry -> describe(entry.getValue()), (a, b) -> a, LinkedHashMap::new)));
        diagnosis.put("hypotheses", hypotheses);
        return diagnosis;
    }

    private static Map<String, Object> hypothesis(String id, String name, String expected, Map<String, Object> actual, String verdict) {
        Map<String, Object> hypothesis = new LinkedHashMap<>();
        hypothesis.put("id", id);
        hypothesis.put("name", name);
        hypothesis.put("expected", expected);
        hypothesis.put("actual", actual);
        hypothesis.put("verdict", verdict);
        return hypothesis;
    }

    @SuppressWarnings("unchecked")
    private static String verdict(Map<String, Object> diagnosis, String id) {
        return ((List<Map<String, Object>>) diagnosis.get("hypotheses")).stream()
            .filter(hypothesis -> id.equals(hypothesis.get("id")))
            .map(hypothesis -> (String) hypothesis.get("verdict"))
            .findFirst().orElseThrow();
    }

    private static Object describe(PaymentLookupResult result) {
        if (result instanceof PaymentLookupResult.Found found) {
            return found.transactions().stream()
                .map(tx -> Map.of("transactionKey", tx.transactionKey(), "providerOrderId", tx.providerOrderId(),
                    "amount", tx.amount(), "status", tx.status().name()))
                .toList();
        }
        return "UNAVAILABLE";
    }

    /** 단계 기록. 주문마다 시도 · PG 거래 · 내부 상태 · 예약 · create 수를 같은 행에 묶음 */
    private Map<String, Object> stageRecord(String stage, List<Long> orderIds, Map<String, Object> extra) throws Exception {
        List<Map<String, Object>> orders = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select o.id as order_id, o.status as order_status, o.confirmed_at, p.attempt_key, p.provider_order_id,
                       p.transaction_key, p.status as payment_status, p.reservation_status, p.conflict_reason
                from orders o join payment p on p.order_id = o.id
                where o.id in (:ids) order by o.id
                """, Map.of("ids", orderIds))) {
            Map<String, Object> order = new LinkedHashMap<>(row);
            String providerOrderId = (String) row.get("provider_order_id");
            order.put("confirmed_at", row.get("confirmed_at") == null ? null : row.get("confirmed_at").toString());
            order.put("pgTransactions", pg.transactionsOf(providerOrderId).stream()
                .map(tx -> Map.of("transactionKey", tx.transactionKey(), "status", tx.status(), "amount", tx.amount()))
                .toList());
            order.put("pgCreateCount", pg.createCountOf(providerOrderId));
            orders.add(order);
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("stage", stage);
        record.put("observedAt", CLOCK.instant().toString());
        record.put("recordedAt", Instant.now().toString());
        record.put("orderIds", orderIds);
        record.put("orders", orders);
        record.put("productStock", stock());
        record.put("pgTotals", Map.of("transactions", pg.transactions().size(), "creates", pg.createCount(),
            "lookups", pg.lookupCount(), "duplicateCreates", pg.duplicateCreates()));
        record.put("health", objectMapper.readTree(management("/actuator/health").body()));
        record.put("prometheusUnresolved", management("/actuator/prometheus").body().lines()
            .filter(line -> line.startsWith("commerce_payment_unresolved"))
            .toList());
        record.putAll(extra);
        return record;
    }

    /** 진단 조회 전후 비교용. 결제 · 주문 · 재고 · Outbox */
    private Map<String, Object> commerceFingerprint() {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("payment", jdbc.queryForList(
            "select order_id, status, reservation_status, transaction_key, conflict_reason from payment order by order_id", Map.of()));
        fingerprint.put("orders", jdbc.queryForList("select id, status, payment_amount from orders order by id", Map.of()));
        fingerprint.put("stock", stock());
        fingerprint.put("outbox", outboxCount());
        return fingerprint;
    }

    /** fixture 가 받은 주문 단위 조회 수. 장애 주문을 아무도 조회하지 않았다는 근거 */
    private Map<String, Integer> lookupsByOrder(List<Long> orderIds) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Long orderId : orderIds) {
            String providerOrderId = Payment.providerOrderIdOf(orderId);
            counts.put(providerOrderId, (int) pg.requests().stream()
                .filter(request -> "GET".equals(request.method()))
                .filter(request -> request.queryParam("orderId").filter(providerOrderId::equals).isPresent())
                .count());
        }
        return counts;
    }

    private List<Long> createOrders() throws Exception {
        List<Long> orderIds = new ArrayList<>();
        String body = "{\"items\": [{\"productId\": " + product.getId() + ", \"quantity\": 1}]}";
        for (int i = 0; i < ORDERS_PER_SET; i++) {
            orderIds.add(postAs("/api/v1/orders", body).at("/data/id").asLong());
        }
        return List.copyOf(orderIds);
    }

    private JsonNode confirm(Long orderId) throws Exception {
        return postAs("/api/v1/orders/" + orderId + "/confirm",
            "{\"paymentAttemptKey\": \"incident-" + orderId + "\", \"cardType\": \"TEST\", \"cardNo\": \"0000-0000-0000-0000\"}");
    }

    private JsonNode reconcile(Long orderId) throws Exception {
        return postAs("/api/v1/orders/" + orderId + "/payment/reconcile", "");
    }

    /** 본문 상태는 적용하지 않고 조회 결과만 쓰는지 확인하려고 SUCCESS 로 조작함 (W5 D-62) */
    private void forgedCallback(Long orderId) throws Exception {
        postAs("/api/v1/payments/callback", "{\"orderId\": \"" + Payment.providerOrderIdOf(orderId)
            + "\", \"transactionKey\": \"FORGED\", \"status\": \"SUCCESS\"}");
    }

    /** 수락(PENDING)된 거래를 PG 쪽에서 최종 SUCCESS 로 만듦 */
    private void completeAtPg(Long orderId) {
        List<TestPgServer.Transaction> transactions = pg.transactionsOf(Payment.providerOrderIdOf(orderId));
        assertThat(transactions).as("주문 %d 의 거래", orderId).hasSize(1);
        pg.complete(transactions.get(0).transactionKey(), "SUCCESS");
    }

    private void assertSingleSuccessTransaction(Long orderId) {
        String providerOrderId = Payment.providerOrderIdOf(orderId);
        assertThat(pg.transactionsOf(providerOrderId))
            .singleElement()
            .satisfies(transaction -> {
                assertThat(transaction.status()).isEqualTo("SUCCESS");
                assertThat(transaction.amount()).isEqualTo(PRICE);
            });
        assertThat(pg.createCountOf(providerOrderId)).isEqualTo(1);
    }

    /** 메모리 값이 아니라 매번 DB 에서 다시 읽음 */
    private Map<Long, OrderState> states(List<Long> orderIds) {
        return jdbc.query("""
                select o.id, o.status as order_status, p.status as payment_status, p.reservation_status, p.transaction_key
                from orders o join payment p on p.order_id = o.id
                where o.id in (:ids)
                """,
            Map.of("ids", orderIds),
            (rs, rowNum) -> new OrderState(rs.getLong("id"), rs.getString("order_status"), rs.getString("payment_status"),
                rs.getString("reservation_status"), rs.getString("transaction_key")))
            .stream()
            .collect(Collectors.toMap(OrderState::orderId, state -> state));
    }

    private int stock() {
        return jdbc.queryForObject("select stock from product where id = :id", Map.of("id", product.getId()), Integer.class);
    }

    private long pointBalance() {
        return jdbc.queryForObject("select balance from points where user_id = :id", Map.of("id", user.getId()), Long.class);
    }

    private int couponCount() {
        return jdbc.queryForObject("select count(*) from coupon where user_id = :id", Map.of("id", user.getId()), Integer.class);
    }

    private int outboxCount() {
        return jdbc.queryForObject("select count(*) from order_confirmed_outbox", Map.of(), Integer.class);
    }

    private Map<Long, Integer> outboxCountByOrder(List<Long> orderIds) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        jdbc.query("select order_id, count(*) as cnt from order_confirmed_outbox where order_id in (:ids) group by order_id",
            Map.of("ids", orderIds), rs -> {
                counts.put(rs.getLong("order_id"), rs.getInt("cnt"));
            });
        return counts;
    }

    /** DB 에 UTC 벽시계 값으로 저장된 최초 확정 시각 */
    private List<LocalDateTime> confirmedAt(List<Long> orderIds) {
        return jdbc.queryForList("select confirmed_at from orders where id in (:ids) order by id",
            Map.of("ids", orderIds), LocalDateTime.class);
    }

    private String healthStatus() throws Exception {
        HttpResponse<String> response = management("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).path("status").asText();
    }

    /** gauge 는 갱신 주기만큼 늦으므로 유한한 시간 안에 기대값이 될 때까지 봄 (D-202) */
    private double awaitUnresolved(String paymentStatus, double expected) throws Exception {
        long deadline = System.nanoTime() + GAUGE_TIMEOUT.toNanos();
        double value;
        do {
            value = unresolved(paymentStatus);
            if (value == expected) {
                return value;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return value;
    }

    private double unresolved(String paymentStatus) throws Exception {
        Matcher matcher = UNRESOLVED_LINE.matcher(management("/actuator/prometheus").body());
        while (matcher.find()) {
            if (matcher.group(1).contains("status=\"" + paymentStatus + "\"")) {
                return Double.parseDouble(matcher.group(2));
            }
        }
        throw new AssertionError("prometheus 응답에 " + paymentStatus + " 미종결 지표가 없음");
    }

    private List<String> unknownCreateLogs() {
        Pattern pattern = Pattern.compile("PG 생성 결과\\. providerOrderId=(\\S+) .*result=UNKNOWN");
        return paymentLogs.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .map(pattern::matcher)
            .filter(Matcher::find)
            .map(matcher -> matcher.group(1))
            .toList();
    }

    private List<String> logLines(String prefix, List<String> providerOrderIds) {
        return paymentLogs.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(line -> line.startsWith(prefix))
            .filter(line -> providerOrderIds.stream().anyMatch(id -> line.contains("providerOrderId=" + id + " ")))
            .toList();
    }

    private HttpResponse<String> management(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postAs(String path, String body) throws Exception {
        String response = mvc.perform(post(path)
                .header(USER_ID_HEADER, String.valueOf(user.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is2xxSuccessful())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private int postStatus(String path, User caller) throws Exception {
        return mvc.perform(post(path)
                .header(USER_ID_HEADER, String.valueOf(caller.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(""))
            .andReturn().getResponse().getStatus();
    }

    /** 단계마다 앞으로만 옮기는 시계 */
    private static final class IncidentClock extends Clock {
        private volatile Instant instant;

        IncidentClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
