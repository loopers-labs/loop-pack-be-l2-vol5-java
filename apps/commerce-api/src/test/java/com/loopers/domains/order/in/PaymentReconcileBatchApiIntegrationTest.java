package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 일괄 조회 확정은 사람이 고정한 orderId 목록만 기존 수동 reconcile 경로로 처리한다. 주문 사이 간격은 운영 동작이라 test 프로필에서 0이다.
// "연속 실패"가 무엇을 세는지 정해지기 전이라 혼합 목록은 멈춤·대상 아님이 두 건 넘게 이어지지 않게 배치한다.
@SpringBootTest
@AutoConfigureMockMvc
class PaymentReconcileBatchApiIntegrationTest {
    private static final String PATH = "/api-admin/v1/payments/reconcile-batch";
    private static final long MISSING_ORDER_ID = 999_999L;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private UserJpaRepository users;
    @Autowired private TestPgFixture pg;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    private TestPgServer server;
    private long productId;

    @BeforeEach
    void prepare() {
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brandId = brands.create("브랜드").id();
        productId = products.create(brandId, "상품", 1000, 100).id();
    }

    @AfterEach
    void clean() throws Exception {
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("혼합 목록은 주문마다 확정·멈춤(조회 실패·불일치·계속 대기)·이미 종결·대상 아님으로 나뉘고 create를 보내지 않는다")
    void reportsEachOrderOutcomeWithoutCreate() throws Exception {
        long lookupFailed = pendingOrder();
        server.lookupErrorFor(providerOrderId(lookupFailed));
        long verified = pendingOrder();
        server.changeStatus(transactionKey(verified), "SUCCESS");
        long mismatched = pendingOrder();
        server.replaceTransaction(transactionKey(mismatched), providerOrderId(mismatched), 999, "SUCCESS");
        long alreadyConfirmed = pendingOrder();
        server.changeStatus(transactionKey(alreadyConfirmed), "SUCCESS");
        orders.reconcilePayment(alreadyConfirmed);
        long stillPending = pendingOrder();
        long draft = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        long creates = server.createCalls();
        long lookups = server.lookupCalls();

        JsonNode data = batch(List.of(MISSING_ORDER_ID, lookupFailed, verified, mismatched, alreadyConfirmed, stillPending, draft));

        assertThat(data.get("requested").asInt()).isEqualTo(7);
        assertThat(data.get("processed").asInt()).isEqualTo(7);
        assertThat(data.get("stopReason").isNull()).isTrue();
        Map<Long, JsonNode> results = resultsByOrderId(data);
        assertThat(orderIds(data)).containsExactly(MISSING_ORDER_ID, lookupFailed, verified, mismatched, alreadyConfirmed,
            stillPending, draft);
        item(results.get(MISSING_ORDER_ID), "NOT_APPLICABLE", null, "NOT_FOUND", null, null);
        item(results.get(lookupFailed), "STOPPED", "UNCERTAIN", "LOOKUP_FAILED", "PAYMENT_PENDING", "PAYMENT_PENDING");
        item(results.get(verified), "RESOLVED", "VERIFIED", null, "PAYMENT_PENDING", "CONFIRMED");
        item(results.get(mismatched), "STOPPED", "REVIEW", "TRANSACTION_MISMATCH", "PAYMENT_PENDING", "PAYMENT_PENDING");
        item(results.get(alreadyConfirmed), "ALREADY_CLOSED", null, null, "CONFIRMED", "CONFIRMED");
        item(results.get(stillPending), "STOPPED", "VERIFIED", "STILL_PENDING", "PAYMENT_PENDING", "PAYMENT_PENDING");
        item(results.get(draft), "NOT_APPLICABLE", null, "NOT_PG", "DRAFT", "DRAFT");
        assertThat(results.get(verified).get("beforeProviderStatus").asText()).isEqualTo("PENDING");
        assertThat(results.get(verified).get("afterProviderStatus").asText()).isEqualTo("SUCCESS");
        assertThat(results.get(mismatched).get("afterProviderStatus").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(orderState(lookupFailed)).isEqualTo("PAYMENT_PENDING/PENDING");
        assertThat(orderState(verified)).isEqualTo("CONFIRMED/SUCCESS");
        assertThat(orderState(mismatched)).isEqualTo("PAYMENT_PENDING/NEEDS_REVIEW");
        assertThat(orderState(stillPending)).isEqualTo("PAYMENT_PENDING/PENDING");
        assertThat(server.createCalls()).isEqualTo(creates);
        assertThat(server.lookupCalls() - lookups).as("종결·대상 아님은 PG를 조회하지 않는다").isEqualTo(4);
    }

    @Test
    @DisplayName("조회 실패가 연속 3건이면 남은 주문은 PG 조회 없이 미처리(중단)로 표시하고 결제 대기로 둔다")
    void stopsAfterConsecutiveLookupFailures() throws Exception {
        List<Long> failing = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            long orderId = pendingOrder();
            server.lookupErrorFor(providerOrderId(orderId));
            failing.add(orderId);
        }
        long remainingFirst = pendingOrder();
        server.changeStatus(transactionKey(remainingFirst), "SUCCESS");
        long remainingSecond = pendingOrder();
        server.changeStatus(transactionKey(remainingSecond), "SUCCESS");
        long lookups = server.lookupCalls();
        List<Long> requested = new ArrayList<>(failing);
        requested.add(remainingFirst);
        requested.add(remainingSecond);

        JsonNode data = batch(requested);

        assertThat(data.get("requested").asInt()).isEqualTo(5);
        assertThat(data.get("processed").asInt()).isEqualTo(3);
        assertThat(data.get("stopReason").asText()).isEqualTo("CONSECUTIVE_FAILURES");
        Map<Long, JsonNode> results = resultsByOrderId(data);
        assertThat(orderIds(data)).containsExactlyElementsOf(requested);
        for (long orderId : failing) {
            item(results.get(orderId), "STOPPED", "UNCERTAIN", "LOOKUP_FAILED", "PAYMENT_PENDING", "PAYMENT_PENDING");
        }
        assertThat(results.get(remainingFirst).get("outcome").asText()).isEqualTo("SKIPPED");
        assertThat(results.get(remainingFirst).get("reason").asText()).isEqualTo("CONSECUTIVE_FAILURES");
        assertThat(results.get(remainingSecond).get("outcome").asText()).isEqualTo("SKIPPED");
        assertThat(results.get(remainingSecond).get("reason").asText()).isEqualTo("CONSECUTIVE_FAILURES");
        assertThat(orderState(remainingFirst)).isEqualTo("PAYMENT_PENDING/PENDING");
        assertThat(orderState(remainingSecond)).isEqualTo("PAYMENT_PENDING/PENDING");
        assertThat(server.lookupCalls() - lookups).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 목록을 다시 보내면 모두 이미 종결이고 주문·결제 상태·Outbox·종결 counter·PG 호출이 그대로다")
    void secondRunOfSameListChangesNothing() throws Exception {
        long first = pendingOrder();
        server.changeStatus(transactionKey(first), "SUCCESS");
        long second = pendingOrder();
        server.changeStatus(transactionKey(second), "SUCCESS");
        List<Long> requested = List.of(first, second);

        JsonNode firstRun = batch(requested);
        assertThat(outcomes(firstRun)).containsExactly("RESOLVED", "RESOLVED");
        List<Map<String, Object>> ordersAfterFirst = jdbc.queryForList("select id, status from orders order by id");
        List<Map<String, Object>> paymentsAfterFirst = jdbc.queryForList(
            "select order_id, result, provider_status, transaction_key from payment_records order by order_id");
        long outboxAfterFirst = jdbc.queryForObject("select count(*) from order_outbox", Long.class);
        double confirmedAfterFirst = confirmedOutcomes();
        long createsAfterFirst = server.createCalls();
        long lookupsAfterFirst = server.lookupCalls();

        JsonNode secondRun = batch(requested);

        assertThat(outcomes(secondRun)).containsExactly("ALREADY_CLOSED", "ALREADY_CLOSED");
        assertThat(jdbc.queryForList("select id, status from orders order by id")).isEqualTo(ordersAfterFirst);
        assertThat(jdbc.queryForList(
            "select order_id, result, provider_status, transaction_key from payment_records order by order_id"))
            .isEqualTo(paymentsAfterFirst);
        assertThat(outboxAfterFirst).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox", Long.class)).isEqualTo(outboxAfterFirst);
        assertThat(confirmedOutcomes()).isEqualTo(confirmedAfterFirst);
        assertThat(server.createCalls()).isEqualTo(createsAfterFirst);
        assertThat(server.lookupCalls()).isEqualTo(lookupsAfterFirst);
    }

    @Test
    @DisplayName("목록에 없는 결제 대기 주문은 PG가 성공이어도 조회·확정하지 않는다")
    void leavesOrdersOutsideListUntouched() throws Exception {
        long listed = pendingOrder();
        server.changeStatus(transactionKey(listed), "SUCCESS");
        long unlisted = pendingOrder();
        server.changeStatus(transactionKey(unlisted), "SUCCESS");
        long lookups = server.lookupCalls();

        batch(List.of(listed));

        assertThat(orderState(listed)).isEqualTo("CONFIRMED/SUCCESS");
        assertThat(orderState(unlisted)).isEqualTo("PAYMENT_PENDING/PENDING");
        assertThat(server.lookupCalls() - lookups).isEqualTo(1);
    }

    @Test
    @DisplayName("확인 필요 주문은 PG 단일 거래가 일치하는 성공이면 수동 재조회처럼 확인 필요를 해제하고 확정한다")
    void resolvesNeedsReviewWithCleanSuccess() throws Exception {
        long reviewed = needsReviewOrder();
        server.replaceTransaction(transactionKey(reviewed), providerOrderId(reviewed), 1000, "SUCCESS");

        JsonNode data = batch(List.of(reviewed));

        JsonNode result = resultsByOrderId(data).get(reviewed);
        item(result, "RESOLVED", "VERIFIED", null, "PAYMENT_PENDING", "CONFIRMED");
        assertThat(result.get("beforeProviderStatus").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.get("afterProviderStatus").asText()).isEqualTo("SUCCESS");
        assertThat(orderState(reviewed)).isEqualTo("CONFIRMED/SUCCESS");
    }

    @Test
    @DisplayName("확인 필요 주문의 PG 거래가 여전히 불일치하면 멈춤(TRANSACTION_MISMATCH)이고 확인 필요를 유지한다")
    void keepsNeedsReviewWhileStillMismatched() throws Exception {
        long reviewed = needsReviewOrder();

        JsonNode data = batch(List.of(reviewed));

        JsonNode result = resultsByOrderId(data).get(reviewed);
        item(result, "STOPPED", "REVIEW", "TRANSACTION_MISMATCH", "PAYMENT_PENDING", "PAYMENT_PENDING");
        assertThat(result.get("beforeProviderStatus").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.get("afterProviderStatus").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(orderState(reviewed)).isEqualTo("PAYMENT_PENDING/NEEDS_REVIEW");
    }

    @Test
    @DisplayName("같은 orderId가 여러 번 있으면 처음 위치에서 한 번만 처리하고 결과도 한 줄이다")
    void processesDuplicateOrderIdOnce() throws Exception {
        long repeated = pendingOrder();
        server.changeStatus(transactionKey(repeated), "SUCCESS");
        long other = pendingOrder();
        long lookups = server.lookupCalls();

        JsonNode data = batch(List.of(repeated, other, repeated));

        assertThat(data.get("requested").asInt()).isEqualTo(2);
        assertThat(orderIds(data)).containsExactly(repeated, other);
        assertThat(outcomes(data)).containsExactly("RESOLVED", "STOPPED");
        assertThat(server.lookupCalls() - lookups).isEqualTo(2);
    }

    @Test
    @DisplayName("51건이면 400이고 목록 안의 주문을 하나도 조회·확정하지 않는다")
    void rejectsMoreThanFiftyOrders() throws Exception {
        long pending = pendingOrder();
        server.changeStatus(transactionKey(pending), "SUCCESS");
        List<Long> requested = new ArrayList<>(List.of(pending));
        LongStream.range(0, 50).forEach(index -> requested.add(MISSING_ORDER_ID + index));
        long lookups = server.lookupCalls();

        admin(body(requested)).andExpect(status().isBadRequest());

        assertThat(orderState(pending)).isEqualTo("PAYMENT_PENDING/PENDING");
        assertThat(server.lookupCalls()).isEqualTo(lookups);
    }

    @Test
    @DisplayName("50건은 한도 안이라 받아서 50줄 결과를 준다")
    void acceptsFiftyOrders() throws Exception {
        List<Long> requested = LongStream.range(0, 50).mapToObj(index -> MISSING_ORDER_ID + index).toList();

        JsonNode data = batch(requested);

        assertThat(data.get("requested").asInt()).isEqualTo(50);
        assertThat(data.get("results")).hasSize(50);
    }

    @ParameterizedTest(name = "본문={0}")
    @ValueSource(strings = {
        "{\"orderIds\":[]}",
        "{}",
        "{\"orderIds\":null}",
        "{\"orderIds\":\"1\"}",
        "{\"orderIds\":[0]}",
        "{\"orderIds\":[-1]}",
        "{\"orderIds\":[\"a\"]}",
        "{\"orderIds\":[1.5]}",
        "{\"orderIds\":[null]}"
    })
    @DisplayName("빈 목록·목록 누락·양수 정수가 아닌 orderId는 400이다")
    void rejectsMalformedBody(String body) throws Exception {
        admin(body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("관리자가 아니거나 식별되지 않은 요청은 유효한 CSRF가 있어도 403이고 PG를 조회하지 않는다")
    void rejectsNonAdmin() throws Exception {
        long pending = pendingOrder();
        server.changeStatus(transactionKey(pending), "SUCCESS");
        long lookups = server.lookupCalls();
        String body = body(List.of(pending));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(csrf()).with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body).with(csrf()))
            .andExpect(status().isForbidden());

        assertThat(orderState(pending)).isEqualTo("PAYMENT_PENDING/PENDING");
        assertThat(server.lookupCalls()).isEqualTo(lookups);
    }

    private long pendingOrder() {
        long orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        orders.confirm(1, orderId, TestPgFixture.command());
        return orderId;
    }

    // 금액 불일치 거래를 수동 재조회로 판정해 확인 필요(NEEDS_REVIEW)로 만든다. 서비스 경로가 만드는 상태다.
    private long needsReviewOrder() {
        long orderId = pendingOrder();
        server.replaceTransaction(transactionKey(orderId), providerOrderId(orderId), 999, "SUCCESS");
        orders.reconcilePayment(orderId);
        assertThat(orderState(orderId)).isEqualTo("PAYMENT_PENDING/NEEDS_REVIEW");
        return orderId;
    }

    private JsonNode batch(List<Long> orderIds) throws Exception {
        String response = admin(body(orderIds))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data");
    }

    private ResultActions admin(String body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .with(csrf()).with(user("admin").roles("ADMIN")));
    }

    private static String body(List<Long> orderIds) {
        return "{\"orderIds\":" + orderIds.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]")) + "}";
    }

    private static void item(JsonNode item, String outcome, String assessment, String reason, String beforeStatus,
        String afterStatus) {
        assertThat(item).as("결과 항목").isNotNull();
        assertThat(text(item, "outcome")).as("outcome").isEqualTo(outcome);
        assertThat(text(item, "assessment")).as("assessment").isEqualTo(assessment);
        assertThat(text(item, "reason")).as("reason").isEqualTo(reason);
        assertThat(text(item, "beforeStatus")).as("beforeStatus").isEqualTo(beforeStatus);
        assertThat(text(item, "afterStatus")).as("afterStatus").isEqualTo(afterStatus);
    }

    private static String text(JsonNode item, String field) {
        JsonNode value = item.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Map<Long, JsonNode> resultsByOrderId(JsonNode data) {
        Map<Long, JsonNode> results = new HashMap<>();
        data.get("results").forEach(item -> results.put(item.get("orderId").asLong(), item));
        return results;
    }

    private static List<Long> orderIds(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.get("results").forEach(item -> ids.add(item.get("orderId").asLong()));
        return ids;
    }

    private static List<String> outcomes(JsonNode data) {
        List<String> outcomes = new ArrayList<>();
        data.get("results").forEach(item -> outcomes.add(item.get("outcome").asText()));
        return outcomes;
    }

    private String orderState(long orderId) {
        return jdbc.queryForObject("select concat(o.status, '/', p.provider_status) from orders o"
            + " join payment_records p on p.order_id = o.id where o.id = ?", String.class, orderId);
    }

    private double confirmedOutcomes() {
        Counter counter = meterRegistry.find("commerce.payment.outcome").tag("result", "confirmed").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static String providerOrderId(long orderId) {
        return String.format("LP-ORD-%010d", orderId);
    }

    private String transactionKey(long orderId) {
        String providerOrderId = providerOrderId(orderId);
        return server.transactions().stream()
            .filter(transaction -> transaction.orderId().equals(providerOrderId))
            .findFirst().orElseThrow().transactionKey();
    }
}
