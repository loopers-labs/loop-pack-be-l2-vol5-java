package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.utils.DatabaseCleanUp;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 사람 조치(계획 2-4 P7·F12): 미확인 목록의 status에 HANDED_TO_HUMAN·REFUND_UNKNOWN을 주면 환불 확인 대상(payment_refund_cases)을 보여 준다.
// 여섯 상태 대상을 모두 두고 열린 시각을 삽입 순서와 다르게 둬, 상태 조건이 빠지거나 정렬이 ID 기준이면 드러나게 한다.
// 같은 DB에 결제 대기 PG 결제도 둬 기존 status 값의 원천·항목 모양이 바뀌지 않음을 함께 본다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentVerificationApiIntegrationTest.FixedClockConfig.class)
class AdminUnresolvedRefundCaseApiIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final String PATH = "/api-admin/v1/payments/unresolved";
    private static final String SECRET_KEY = "TX-SECRET-KEY-1";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    private long pending;
    private long handedOld;
    private long handedNew;
    private long handedMiddle;
    private long refundUnknown;

    @BeforeEach
    void prepare() {
        pending = pgOrder("PAYMENT_PENDING", "PENDING", "PENDING", null, NOW.minusSeconds(300));
        handedNew = refundCase("HANDED_TO_HUMAN", "TRANSACTION_MISMATCH", SECRET_KEY, NOW.minusSeconds(3_600));
        handedOld = refundCase("HANDED_TO_HUMAN", "HANDOFF_DEADLINE", null, NOW.minusSeconds(200_000));
        handedMiddle = refundCase("HANDED_TO_HUMAN", "RESULT_REVERSED", "TX-2", NOW.minusSeconds(86_400));
        refundUnknown = refundCase("REFUND_UNKNOWN", null, "TX-3", NOW.minusSeconds(7_200));
        refundCase("TRACKING", null, null, NOW.minusSeconds(300_000));
        refundCase("REFUND_REQUESTED", null, "TX-4", NOW.minusSeconds(300_000));
        refundCase("REFUNDED", null, "TX-5", NOW.minusSeconds(300_000));
        refundCase("CLOSED_NO_CHARGE", null, null, NOW.minusSeconds(300_000));
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("status=HANDED_TO_HUMAN이면 그 상태 환불 확인 대상만 오래 열린 순으로 주고 카드·사용자·거래 키 값 없이 기한·사유를 준다")
    void listsHandedToHumanRefundCasesOldestFirst() throws Exception {
        String body = body(get(PATH).param("status", "HANDED_TO_HUMAN"));
        JsonNode data = objectMapper.readTree(body).get("data");

        assertThat(orderIds(data)).containsExactly(handedOld, handedMiddle, handedNew);
        JsonNode newest = data.get(2);
        assertThat(fieldNames(newest)).containsExactlyInAnyOrder("orderId", "providerOrderId", "providerId", "amount",
            "refundStatus", "priorProviderStatus", "hasTransactionKey", "reason", "openedAt", "elapsedSeconds", "handoffAt",
            "promiseAt", "checkAttempts", "nextCheckAt", "refundRequestedAt", "concludedAt");
        assertThat(newest.get("providerOrderId").asText()).isEqualTo("PO-" + handedNew);
        assertThat(newest.get("providerId").asText()).isEqualTo("test-pg");
        assertThat(newest.get("amount").asLong()).isEqualTo(1000);
        assertThat(newest.get("refundStatus").asText()).isEqualTo("HANDED_TO_HUMAN");
        assertThat(newest.get("priorProviderStatus").asText()).isEqualTo("PENDING");
        assertThat(newest.get("hasTransactionKey").asBoolean()).isTrue();
        assertThat(newest.get("reason").asText()).isEqualTo("TRANSACTION_MISMATCH");
        assertThat(newest.get("openedAt").asText()).isEqualTo("2026-10-20T23:00:00Z");
        assertThat(newest.get("elapsedSeconds").asLong()).isEqualTo(3_600);
        assertThat(newest.get("handoffAt").asText()).isEqualTo("2026-10-22T23:00:00Z");
        assertThat(newest.get("promiseAt").asText()).isEqualTo("2026-10-24T23:00:00Z");
        assertThat(newest.get("checkAttempts").asInt()).isEqualTo(3);
        assertThat(newest.get("nextCheckAt").isNull()).isTrue();
        assertThat(newest.get("refundRequestedAt").isNull()).isTrue();
        assertThat(newest.get("concludedAt").asText()).isEqualTo("2026-10-20T23:30:00Z");
        assertThat(data.get(0).get("hasTransactionKey").asBoolean()).isFalse();
        assertThat(body).doesNotContain("userId", "card", SECRET_KEY);
    }

    @Test
    @DisplayName("status=REFUND_UNKNOWN이면 결과 모름 대상만 주고 두 값을 함께 주면 두 상태 대상을 오래 열린 순으로 준다")
    void listsRefundUnknownAloneOrWithHandedToHuman() throws Exception {
        assertThat(orderIds(list(get(PATH).param("status", "REFUND_UNKNOWN")))).containsExactly(refundUnknown);
        assertThat(orderIds(list(get(PATH).param("status", "HANDED_TO_HUMAN", "REFUND_UNKNOWN"))))
            .containsExactly(handedOld, handedMiddle, refundUnknown, handedNew);
    }

    @Test
    @DisplayName("환불 확인 대상도 page·size를 오래 열린 순 정렬 위에서 자른다")
    void pagesRefundCasesOverOldestFirstOrder() throws Exception {
        assertThat(orderIds(list(get(PATH).param("status", "HANDED_TO_HUMAN").param("page", "1").param("size", "2"))))
            .containsExactly(handedNew);
        assertThat(orderIds(list(get(PATH).param("status", "HANDED_TO_HUMAN").param("page", "2").param("size", "2"))))
            .isEmpty();
    }

    @Test
    @DisplayName("환불 확인 대상의 경과 시간 필터는 열린 시각 기준이고 경과 시간이 기준과 같으면 포함한다")
    void filtersRefundCasesByOpenedAtAge() throws Exception {
        assertThat(orderIds(list(get(PATH).param("status", "HANDED_TO_HUMAN", "REFUND_UNKNOWN")
            .param("minAgeSeconds", "7200")))).containsExactly(handedOld, handedMiddle, refundUnknown);
        assertThat(orderIds(list(get(PATH).param("status", "HANDED_TO_HUMAN").param("minAgeSeconds", "86401"))))
            .containsExactly(handedOld);
    }

    @Test
    @DisplayName("결제 대기 상태와 환불 확인 상태를 한 요청에 섞으면 원천이 달라 400이다")
    void rejectsMixedSources() throws Exception {
        mvc.perform(get(PATH).param("status", "PENDING", "HANDED_TO_HUMAN").with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("환불 확인 대상이 있어도 status가 없거나 기존 값이면 결제 대기 PG 결제만 기존 항목 모양으로 준다")
    void keepsExistingStatusesOnPendingPayments() throws Exception {
        JsonNode all = list(get(PATH));
        JsonNode pendingOnly = list(get(PATH).param("status", "PENDING"));

        assertThat(orderIds(all)).containsExactly(pending);
        assertThat(orderIds(pendingOnly)).containsExactly(pending);
        assertThat(fieldNames(all.get(0))).containsExactlyInAnyOrder("orderId", "providerOrderId", "providerId",
            "providerStatus", "startedAt", "elapsedSeconds", "lookupAttempts", "nextLookupAt", "latestAnomaly");
    }

    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = {"TRACKING", "REFUND_REQUESTED", "REFUNDED", "CLOSED_NO_CHARGE", "BOGUS"})
    @DisplayName("사람 조치 대상이 아닌 환불 상태나 모르는 상태는 400이다")
    void rejectsOtherRefundStatuses(String value) throws Exception {
        mvc.perform(get(PATH).param("status", value).with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("관리자가 아니거나 식별되지 않은 요청은 환불 상태를 줘도 유효한 CSRF가 있어도 403이다")
    void rejectsNonAdminForRefundStatuses() throws Exception {
        mvc.perform(get(PATH).param("status", "HANDED_TO_HUMAN").with(csrf()).with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(get(PATH).param("status", "HANDED_TO_HUMAN").with(csrf())).andExpect(status().isForbidden());
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private JsonNode list(MockHttpServletRequestBuilder request) throws Exception {
        return objectMapper.readTree(body(request)).get("data");
    }

    private static List<Long> orderIds(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.forEach(item -> ids.add(item.get("orderId").asLong()));
        return ids;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // 만료 주문과 그 결제 기록, 환불 확인 대상을 함께 만든다. 결론 시각은 열린 지 30분 뒤로 둔다.
    private long refundCase(String status, String reason, String transactionKey, Instant openedAt) {
        long orderId = pgOrder("PAYMENT_EXPIRED", "EXPIRED", "EXPIRED", transactionKey, openedAt);
        boolean concluded = List.of("HANDED_TO_HUMAN", "REFUNDED", "CLOSED_NO_CHARGE").contains(status);
        jdbc.update("insert into payment_refund_cases(order_id, provider_id, provider_order_id, amount, status, "
                + "prior_provider_status, transaction_key, opened_at, handoff_at, promise_at, next_check_at, check_attempts, "
                + "refund_requested_at, concluded_at, reason) values (?, 'test-pg', ?, 1000, ?, 'PENDING', ?, ?, ?, ?, ?, 3, ?, ?, ?)",
            orderId, "PO-" + orderId, status, transactionKey, utc(openedAt), utc(openedAt.plusSeconds(2 * 86_400)),
            utc(openedAt.plusSeconds(4 * 86_400)), concluded ? null : utc(NOW.plusSeconds(60)),
            "REFUND_UNKNOWN".equals(status) || "REFUND_REQUESTED".equals(status) ? utc(openedAt.plusSeconds(60)) : null,
            concluded ? utc(openedAt.plusSeconds(1_800)) : null, reason);
        return orderId;
    }

    private long pgOrder(String orderStatus, String result, String providerStatus, String transactionKey, Instant startedAt) {
        long orderId = order(orderStatus);
        jdbc.update("insert into payment_records(order_id, method, amount, result, attempt_key, provider_id, provider_order_id, "
                + "transaction_key, provider_status, started_at) values (?, 'PG', 1000, ?, ?, 'test-pg', ?, ?, ?, ?)",
            orderId, result, UUID.randomUUID().toString(), "PO-" + orderId, transactionKey, providerStatus, utc(startedAt));
        return orderId;
    }

    private long order(String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into orders(user_id, total, status) values (1, 1000, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, status);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    // JPA는 시각을 UTC 벽시계로 저장한다(jdbc.time_zone UTC). JVM 기본 시간대를 타는 Timestamp 대신 UTC 벽시계 값을 넣는다.
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
