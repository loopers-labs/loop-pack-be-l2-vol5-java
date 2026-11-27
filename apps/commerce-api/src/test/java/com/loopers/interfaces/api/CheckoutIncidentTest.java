package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssuanceTransactions;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.application.point.fixture.PointFixture;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.coupon.CouponCampaignJpaEntity;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.interfaces.api.order.OrderDto;
import com.loopers.interfaces.api.payment.PaymentDto;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class CheckoutIncidentTest {
    private static final LocalPgServer PG = LocalPgServer.start();
    private static final int COHORT_SIZE = 10;
    private static final long UNIT_PRICE = 7_000;
    private static final int REMAINING_STOCK = 3;
    private static final String ORDER = "/api/v1/orders/{orderId}";
    private static final String CONFIRM = ORDER + "/confirm";
    private static final String RECONCILE = ORDER + "/payment/reconcile";
    private static final String RECOVERY_JOURNAL = "reconcile-progress.jsonl";
    private static final Pattern ORDER_URI_LABEL = Pattern.compile("\\buri=\"(/api/v1/orders/[^\"]+)\"");

    @Autowired private OrderPaymentFixture fixture;
    @Autowired private ProductRepository products;
    @Autowired private PointFixture points;
    @Autowired private CouponCampaignJpaRepository campaigns;
    @Autowired private CouponIssuanceTransactions issuance;
    @Autowired private CouponRepository coupons;
    @Autowired private TestRestTemplate http;
    @Autowired private PaymentGateway gateway;
    @Autowired private ObjectMapper mapper;
    @Autowired private DatabaseCleanUp cleanUp;
    @LocalManagementPort private int managementPort;

    @DynamicPropertySource
    static void configurePg(DynamicPropertyRegistry registry) {
        registry.add("payment-provider.base-url", PG::baseUrl);
    }

    private final HttpHeaders headers = new HttpHeaders();
    private long buyerId;
    private long productId;

    @BeforeEach
    void prepareBuyerAndStock() {
        buyerId = fixture.createBuyer();
        headers.set("X-USER-ID", Long.toString(buyerId));
        productId = fixture.createProductWithStock(UNIT_PRICE, 2 * COHORT_SIZE + REMAINING_STOCK).getId();
    }

    @AfterEach
    void cleanUp() {
        PG.reset();
        cleanUp.deleteAllEntities();
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @Test
    void 정상_주문_10건이_각각_PG_성공_거래_한_건과_일치한다() throws IOException {
        runBaseline(newEvidenceDirectory());
    }

    @Test
    void 정상_10건에_이어_새_주문_10건_중_승인_응답을_잃은_3건은_미확인으로_남는다(CapturedOutput output)
            throws IOException, NoSuchAlgorithmException {
        runThroughFault(newEvidenceDirectory(), output);
    }

    @Test
    void 미확인_주문_3건을_기존_PG_거래로_대사하면_새_결제_요청_없이_확정된다(CapturedOutput output)
            throws IOException, NoSuchAlgorithmException {
        Path run = newEvidenceDirectory();
        long startingPoints = 50_000;
        points.createBalance(buyerId, startingPoints);
        var campaign = campaigns.saveAndFlush(new CouponCampaignJpaEntity("대사 검증", true, 1, "할인권"));
        var issuedCoupon = issuance.issue(campaign.getId(), buyerId, new CouponIssuanceTransactions.Completion()).coupon();
        var fault = runThroughFault(run, output);
        Path faultFile = run.resolve("fault.json");
        byte[] faultBeforeRecovery = Files.readAllBytes(faultFile);
        assertThat(PG.isCreateResponseLossEnabled()).as("복구 직전 PG 생성 응답 유실 주입").isFalse();
        int createsBefore = PG.createRequestCount();
        int lookupsBefore = PG.lookupRequestCount();
        var transactionsBefore = PG.transactions();
        var before = readStockPointsAndCoupons();
        assertThat(before).as("대사 직전 남은 재고, 구매자 포인트와 보유 쿠폰")
                .isEqualTo(new StockPointsAndCoupons(REMAINING_STOCK, startingPoints, List.of(issuedCoupon.couponId())));
        var afterEachOrder = reconcileOrders(run, fault, fault.recoveryOrderIds(), before);
        assertThat(afterEachOrder.keySet()).containsExactlyElementsOf(fault.recoveryOrderIds());

        assertThat(PG.createRequestCount()).as("복구 후 PG 결제 생성 요청 수").isEqualTo(createsBefore);
        assertThat(PG.lookupRequestCount() - lookupsBefore).as("복구 중 PG 거래 조회 수").isEqualTo(3);
        assertThat(PG.transactions()).containsExactlyElementsOf(transactionsBefore);
        verifyAndSavePhase(run, "recovery", fault.incidentIds(), fault.approvedUnknownPayments().keySet(), createsBefore);
        mapper.writerWithDefaultPrettyPrinter().writeValue(run.resolve("reconcile-balances.json").toFile(),
                Map.of("before", before, "afterByOrderId", afterEachOrder));
        assertThat(Files.readAllBytes(faultFile)).as("대사 전 보존한 장애 원문").isEqualTo(faultBeforeRecovery);
    }

    private record StockPointsAndCoupons(int availableStock, long pointBalance, List<Long> ownedCouponIds) {}

    private record ReconcileObservation(int httpStatus, JsonNode result, StockPointsAndCoupons after, int pgCreateRequests) {
        void recordIn(Map<String, Object> evidence) {
            evidence.put("orderStatus", result.required("orderStatus"));
            evidence.put("paymentStatus", result.required("paymentStatus"));
            evidence.put("lookupAvailable", result.required("lookupAvailable"));
            evidence.put("transactionKey", result.path("transactionKey"));
            evidence.put("after", after);
            evidence.put("pgCreateRequests", pgCreateRequests);
        }

        boolean lookupUnavailable() {
            return httpStatus == HttpStatus.ACCEPTED.value() && result.required("lookupAvailable").isBoolean()
                    && !result.required("lookupAvailable").booleanValue();
        }
    }

    private ReconcileObservation observeReconciliation(long id, Map<String, Object> evidence) {
        var response = http.exchange(RECONCILE, HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class, id);
        int httpStatus = response.getStatusCode().value();
        evidence.put("httpStatus", httpStatus);
        return new ReconcileObservation(httpStatus, response.getBody().required("data"), readStockPointsAndCoupons(),
                PG.createRequestCount());
    }

    private Map<Long, StockPointsAndCoupons> reconcileOrders(Path run, FaultSnapshot fault, List<Long> orderIds,
                                                             StockPointsAndCoupons before) throws IOException {
        int createsBefore = PG.createRequestCount();
        appendProgress(run, "plan", Map.of("orderIds", orderIds, "before", before, "pgCreateRequests", createsBefore));
        var verifiedBalances = new LinkedHashMap<Long, StockPointsAndCoupons>();
        for (int i = 0; i < orderIds.size(); i++) {
            long id = orderIds.get(i);
            var approved = fault.approvedUnknownPayments().get(id);
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("orderId", id);
            evidence.put("unattemptedOrderIds", orderIds.subList(i + 1, orderIds.size()));
            appendProgress(run, "started", evidence);
            try {
                var observation = observeReconciliation(id, evidence);
                observation.recordIn(evidence);
                var result = observation.result();
                assertThat(observation.after()).as("주문 %d 대사 후 가용 재고, 포인트 잔액, 보유 쿠폰 ID", id).isEqualTo(before);
                assertThat(observation.pgCreateRequests()).isEqualTo(createsBefore);
                assertThat(result.required("orderId").longValue()).isEqualTo(id);
                if (observation.lookupUnavailable()) {
                    evidence.put("reason", "pg_lookup_unavailable");
                    appendProgress(run, "stopped", evidence);
                    break;
                }
                assertThat(observation.httpStatus()).as("주문 %d 대사 HTTP 상태", id).isEqualTo(HttpStatus.OK.value());
                assertThat(PG.lastLookupOrderId()).isEqualTo(approved.providerOrderId());
                assertThat(result.required("lookupAvailable").booleanValue()).isTrue();
                assertThat(result.required("transactionKey").asText()).isEqualTo(approved.transactionKey());
                assertThat(result.required("paymentStatus").asText()).isEqualTo("SUCCESS");
                assertThat(result.required("orderStatus").asText()).isEqualTo("CONFIRMED");
                verifiedBalances.put(id, observation.after());
            } catch (RuntimeException | AssertionError failure) {
                evidence.put("reason", failure instanceof AssertionError ? "verification_failed" : "observation_failed");
                evidence.put("errorType", failure.getClass().getSimpleName());
                appendProgress(run, "stopped", evidence);
                throw failure;
            }
            appendProgress(run, "verified", evidence);
        }
        return verifiedBalances;
    }

    private void appendProgress(Path run, String step, Map<String, ?> fields) throws IOException {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("observedAt", Instant.now().toString());
        entry.put("step", step);
        entry.putAll(fields);
        Files.writeString(run.resolve(RECOVERY_JOURNAL), mapper.writeValueAsString(entry) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void 두번째_대사가_중단되면_완료한_주문의_증거를_보존하고_남은_주문만_재개한다(CapturedOutput output)
            throws IOException, NoSuchAlgorithmException {
        Path run = newEvidenceDirectory();
        points.createBalance(buyerId, 50_000);
        var fault = runThroughFault(run, output);
        byte[] faultBeforeRecovery = Files.readAllBytes(run.resolve("fault.json"));
        var ids = fault.recoveryOrderIds();
        var before = readStockPointsAndCoupons();
        int createsBefore = PG.createRequestCount();
        int lookupsBefore = PG.lookupRequestCount();
        // 두 번째 거래 조회만 거절한다. 실제 대사 API는 미확인 주문을 202로 반환한다.
        PG.respondToLookupRequestsWith(200, 400);
        assertThat(reconcileOrders(run, fault, ids, before)).containsOnlyKeys(ids.getFirst());

        var journal = run.resolve(RECOVERY_JOURNAL);
        var atStop = Files.readAllLines(journal);
        var entries = new ArrayList<JsonNode>();
        for (String line : atStop) {
            entries.add(mapper.readTree(line));
        }
        assertThat(entries).extracting(e -> e.required("step").asText())
                .containsExactly("plan", "started", "verified", "started", "stopped");
        var verified = entries.get(2);
        assertThat(verified.required("orderId").longValue()).isEqualTo(ids.getFirst());
        assertThat(mapper.treeToValue(verified.required("after"), StockPointsAndCoupons.class)).isEqualTo(before);
        var stopped = entries.getLast();
        assertThat(stopped.required("reason").asText()).isEqualTo("pg_lookup_unavailable");
        assertThat(stopped.has("errorType")).isFalse();
        String lookupFailure = "step=pg_lookup orderId=" + ids.get(1) + " providerOrderId="
                + fault.approvedUnknownPayments().get(ids.get(1)).providerOrderId()
                + " outcome=unavailable reason=http_error pgHttpStatus=400";
        assertThat(output.getAll()).contains(lookupFailure);
        assertThat(stopped.required("httpStatus").intValue()).isEqualTo(202);
        assertThat(stopped.required("lookupAvailable").booleanValue()).isFalse();
        assertThat(stopped.required("paymentStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(PG.lookupRequestCount() - lookupsBefore).isEqualTo(2);
        assertThat(PG.createRequestCount()).isEqualTo(createsBefore);
        assertThat(orderDetail(ids.getFirst()).required("status").asText()).isEqualTo("CONFIRMED");

        var remaining = new ArrayList<Long>();
        remaining.add(stopped.required("orderId").longValue());
        stopped.required("unattemptedOrderIds").forEach(node -> remaining.add(node.longValue()));
        assertThat(remaining).containsExactly(ids.get(1), ids.get(2));
        for (long id : remaining) {
            assertThat(orderDetail(id).requiredAt("/payment/status").asText()).isEqualTo("UNKNOWN");
            assertThat(fixture.findReservationByOrderAndProduct(id, productId).status()).isEqualTo(StockReservationStatus.HELD);
            var approved = fault.approvedUnknownPayments().get(id);
            assertThat(gateway.findByOrderId(approved.providerOrderId())).containsExactly(approved);
        }
        int lookupsBeforeResume = PG.lookupRequestCount();
        assertThat(reconcileOrders(run, fault, remaining, before)).containsOnlyKeys(remaining.get(0), remaining.get(1));
        assertThat(PG.lookupRequestCount() - lookupsBeforeResume).isEqualTo(2);
        assertThat(PG.createRequestCount()).isEqualTo(createsBefore);
        assertThat(Files.readAllLines(journal)).startsWith(atStop.toArray(String[]::new));
        verifyAndSavePhase(run, "recovery", fault.incidentIds(), Set.copyOf(ids), createsBefore);
        assertThat(Files.readAllBytes(run.resolve("fault.json"))).isEqualTo(faultBeforeRecovery);
    }

    private StockPointsAndCoupons readStockPointsAndCoupons() {
        int availableStock = products.findById(productId).orElseThrow().getStock();
        var couponIds = coupons.findByUser(buyerId).stream().map(Coupon::couponId).sorted().toList();
        return new StockPointsAndCoupons(availableStock, points.balance(buyerId), couponIds);
    }

    private record FaultSnapshot(List<Long> incidentIds, Map<Long, PaymentGateway.ObservedPayment> approvedUnknownPayments) {
        List<Long> recoveryOrderIds() {
            return approvedUnknownPayments.keySet().stream().sorted().toList();
        }
    }

    private FaultSnapshot runThroughFault(Path run, CapturedOutput output) throws IOException, NoSuchAlgorithmException {
        List<Long> baselineIds = runBaseline(run);
        List<Long> incidentIds = createOrders();
        assertThat(incidentIds).doesNotContainAnyElementsOf(baselineIds);
        Set<Long> affectedIds = Set.of(incidentIds.get(1), incidentIds.get(4), incidentIds.get(7));
        // 실제로 발급된 주문번호로 대상을 고정한다. 그 뒤 요청 도착 순서는 주입 대상에 영향을 주지 않는다.
        var pgOrderIds = affectedIds.stream().map(id -> "LP-ORD-%010d".formatted(id)).collect(Collectors.toSet());
        PG.loseResponseAfterApprovalFor(pgOrderIds);
        try {
            for (long orderId : incidentIds) {
                submitPayment(orderId, affectedIds.contains(orderId));
            }
        } finally {
            PG.loseResponseAfterApprovalFor(Set.of());
        }

        var lookupResults = new LinkedHashMap<Long, PaymentGateway.ObservedPayment>();
        for (long id : affectedIds.stream().sorted().toList()) {
            String pgId = orderDetail(id).requiredAt("/payment/providerOrderId").asText();
            var matches = gateway.findByOrderId(pgId); // 읽기 전용. 쇼핑몰 주문에 결과를 반영하지 않는다.
            assertThat(matches).singleElement().satisfies(tx -> {
                assertThat(tx.providerOrderId()).isEqualTo(pgId);
                assertThat(tx.amount()).isEqualTo(UNIT_PRICE);
                assertThat(tx.status()).isEqualTo(PaymentStatus.SUCCESS);
            });
            lookupResults.put(id, matches.getFirst());
        }

        var boundaryLogs = output.getAll().lines().filter(line -> line.contains("step=pg_create ")
                && line.contains("outcome=unconfirmed ")).toList();
        Files.write(run.resolve("fault-create.log"), boundaryLogs);
        assertThat(boundaryLogs).hasSize(affectedIds.size());
        lookupResults.forEach((id, tx) -> assertThat(boundaryLogs).anyMatch(line -> line.contains(
                "orderId=" + id + " providerOrderId=" + tx.providerOrderId() + " outcome=unconfirmed errorType=")));
        mapper.writerWithDefaultPrettyPrinter().writeValue(run.resolve("fault-pg-lookups.json").toFile(), lookupResults);
        verifyAndSavePhase(run, "fault", incidentIds, affectedIds, 2 * COHORT_SIZE);
        var hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(run.resolve("fault.json")));
        Files.writeString(run.resolve("fault.sha256"), HexFormat.of().formatHex(hash) + "  fault.json\n");
        return new FaultSnapshot(incidentIds, lookupResults);
    }

    private Path newEvidenceDirectory() throws IOException {
        Path reports = Files.createDirectories(Path.of("build", "reports", "checkout-incident"));
        return Files.createTempDirectory(reports, "run-");
    }

    private List<Long> runBaseline(Path run) throws IOException {
        assertThat(PG.isCreateResponseLossEnabled()).as("정상 주문 생성 전 PG 생성 응답 유실 주입").isFalse();
        List<Long> ids = createOrders();
        for (long id : ids) {
            submitPayment(id, false);
        }
        verifyAndSavePhase(run, "baseline", ids, Set.of(), COHORT_SIZE);
        return ids;
    }

    private List<Long> createOrders() {
        var item = new OrderDto.Create(List.of(new OrderDto.ItemRequest(productId, 1)));
        var ids = new ArrayList<Long>();
        for (int i = 0; i < COHORT_SIZE; i++) {
            var order = exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(item, headers), HttpStatus.CREATED);
            ids.add(order.required("orderId").longValue());
        }
        assertThat(ids).hasSize(COHORT_SIZE).doesNotHaveDuplicates();
        return ids;
    }

    private UUID attemptKey(long orderId) {
        return UUID.fromString("017f22e2-79b0-7cc3-98c4-%012x".formatted(orderId));
    }

    private void submitPayment(long orderId, boolean loseResponse) {
        var card = new PaymentDto.ConfirmRequest(attemptKey(orderId), "TEST", "0000-0000-0000-0000");
        var accepted = exchange(CONFIRM, HttpMethod.POST, new HttpEntity<>(card, headers), HttpStatus.ACCEPTED, orderId);
        if (loseResponse) {
            assertThat(accepted.required("paymentStatus").asText()).isEqualTo("UNKNOWN");
            return;
        }
        assertThat(accepted.required("paymentStatus").asText()).isEqualTo("PENDING");
        PG.succeedPaymentForOrder(accepted.required("providerOrderId").asText());
        exchange(RECONCILE, HttpMethod.POST, new HttpEntity<>(headers), HttpStatus.OK, orderId);
    }

    private JsonNode orderDetail(long orderId) {
        return exchange(ORDER, HttpMethod.GET, new HttpEntity<>(headers), HttpStatus.OK, orderId);
    }

    private void verifyAndSavePhase(Path run, String phase, List<Long> ids, Set<Long> affectedIds, int totalPgCreates)
            throws IOException {
        Set<Long> expectedUnknownIds = phase.equals("fault") ? affectedIds : Set.of();
        var orders = new ArrayList<JsonNode>();
        var transactions = new ArrayList<LocalPgServer.Transaction>();
        var reservations = new LinkedHashMap<Long, OrderPaymentFixture.StoredReservation>();
        for (long id : ids) {
            var order = orderDetail(id);
            var payment = order.required("payment");
            var reservation = fixture.findReservationByOrderAndProduct(id, productId);
            assertThat(order.required("orderId").longValue()).isEqualTo(id);
            assertThat(order.required("totalAmount").longValue()).isEqualTo(UNIT_PRICE);
            assertThat(payment.required("paymentAttemptKey").asText()).isEqualTo(attemptKey(id).toString());
            var matching = PG.transactions().stream().filter(tx -> tx.orderId().equals(
                    payment.required("providerOrderId").asText())).toList();
            assertThat(matching).hasSize(1);
            var tx = matching.getFirst();
            assertThat(tx.status()).isEqualTo(LocalPgServer.TransactionStatus.SUCCESS);
            assertThat(tx.amount()).isEqualTo(UNIT_PRICE);
            assertThat(reservation.quantity()).isOne();
            if (expectedUnknownIds.contains(id)) {
                assertThat(order.required("status").asText()).isEqualTo("PAYMENT_PENDING");
                assertThat(payment.required("status").asText()).isEqualTo("UNKNOWN");
                assertThat(order.hasNonNull("paidAmount")).isFalse();
                assertThat(payment.hasNonNull("transactionKey")).isFalse();
                assertThat(reservation.status()).isEqualTo(StockReservationStatus.HELD);
            } else {
                assertThat(order.required("status").asText()).isEqualTo("CONFIRMED");
                assertThat(order.required("paidAmount").longValue()).isEqualTo(UNIT_PRICE);
                assertThat(payment.required("status").asText()).isEqualTo("SUCCESS");
                assertThat(payment.required("transactionKey").asText()).isEqualTo(tx.transactionKey());
                assertThat(reservation.status()).isEqualTo(StockReservationStatus.CONSUMED);
            }
            orders.add(order);
            transactions.add(tx);
            reservations.put(id, reservation);
        }
        assertThat(transactions).hasSize(COHORT_SIZE).extracting(LocalPgServer.Transaction::orderId).doesNotHaveDuplicates();
        assertThat(PG.transactions()).hasSize(totalPgCreates);
        assertThat(PG.createRequestCount()).isEqualTo(totalPgCreates);
        mapper.writerWithDefaultPrettyPrinter().writeValue(run.resolve(phase + ".json").toFile(), Map.of(
                "observedAt", Instant.now().toString(), "orders", orders, "pgTransactions", transactions,
                "pgCreateRequests", PG.createRequestCount(), "pgLookupRequests", PG.lookupRequestCount(),
                "affectedOrderIds", affectedIds.stream().sorted().toList(),
                "reservations", reservations));
        verifyActuator(run, phase, expectedUnknownIds.size());
    }

    private void verifyActuator(Path run, String phase, int expectedUnknown) throws IOException {
        String actuator = "http://127.0.0.1:" + managementPort + "/actuator/";
        var health = http.getForEntity(actuator + "health", String.class);
        String healthFile = phase.equals("baseline") ? "health.json" : phase + "-health.json";
        Files.writeString(run.resolve(healthFile), health.getBody());
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(health.getBody()).required("status").asText()).isEqualTo("UP");
        var metrics = http.getForEntity(actuator + "prometheus", String.class);
        Files.writeString(run.resolve(phase + ".prom"), metrics.getBody());
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String metric : List.of("http_client_requests_seconds_count", "http_server_requests_seconds_count")) {
            var orderUris = metrics.getBody().lines().filter(line -> line.startsWith(metric + "{"))
                    .flatMap(line -> ORDER_URI_LABEL.matcher(line).results())
                    .map(match -> match.group(1)).collect(Collectors.toSet());
            assertThat(orderUris).as("%s 주문 URI 레이블", metric).containsExactlyInAnyOrder(ORDER, CONFIRM, RECONCILE);
        }
        assertThat(metrics.getBody().lines().filter(line -> line.startsWith("commerce_payment_unknown{")
                || line.startsWith("commerce_payment_unknown "))).singleElement().satisfies(sample ->
                assertThat(Double.parseDouble(sample.substring(sample.lastIndexOf(' ') + 1))).isEqualTo(expectedUnknown));
    }

    private JsonNode exchange(String path, HttpMethod method, HttpEntity<?> request, HttpStatus expected, Object... variables) {
        var response = http.exchange(path, method, request, JsonNode.class, variables);
        assertThat(response.getStatusCode()).as("%s %s: %s", method, path, response.getBody()).isEqualTo(expected);
        return response.getBody().required("data");
    }
}
