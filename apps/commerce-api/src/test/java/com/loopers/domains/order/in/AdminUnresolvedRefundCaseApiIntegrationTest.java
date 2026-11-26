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
import org.junit.jupiter.params.provider.CsvSource;
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

// 사람 조치(10주차 5b 계획 5절, S6): 미확인 목록에 refundOwner=PERSON(사람 담당 중)이나 refundMoneyStatus(끝나지 않은 돈의 상태)를 주면
// 환불 확인 대상(payment_refund_cases)을 보여 준다. 옛 status=HANDED_TO_HUMAN·REFUND_UNKNOWN 필터를 미배포라 교체했다(P3).
// 돈·담당 조합을 고루 두고 열린 시각을 삽입 순서와 다르게 둬, 조건이 빠지거나 정렬이 ID 기준이면 드러나게 한다.
// 사람 담당이 닫힌 대상(돈이 끝남)도 둬 "사람 담당 중" 조건이 담당 종료를 보는지 확인한다.
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
    private long personOld;
    private long personMiddle;
    private long personNew;
    private long refundUnknown;
    private long autoChecking;

    @BeforeEach
    void prepare() {
        pending = pgOrder("PAYMENT_PENDING", "PENDING", "PENDING", null, NOW.minusSeconds(300));
        personNew = refundCase("CHARGED", null, "PERSON", "TRANSACTION_MISMATCH", false, SECRET_KEY, NOW.minusSeconds(3_600));
        personOld = refundCase("CHECKING", null, "PERSON", "HANDOFF_DEADLINE", false, null, NOW.minusSeconds(200_000));
        personMiddle = refundCase("CHARGED", null, "PERSON", "RESULT_REVERSED", false, "TX-2", NOW.minusSeconds(86_400));
        refundUnknown = refundCase("REFUND_UNKNOWN", null, "AUTO", null, false, "TX-3", NOW.minusSeconds(7_200));
        refundCase("REFUNDED", "EXTERNAL", "PERSON", "HANDOFF_DEADLINE", true, "TX-7", NOW.minusSeconds(100_000));
        autoChecking = refundCase("CHECKING", null, "AUTO", null, false, null, NOW.minusSeconds(300_000));
        refundCase("REFUND_REQUESTED", null, "AUTO", null, false, "TX-4", NOW.minusSeconds(300_000));
        refundCase("REFUNDED", "SYSTEM", "AUTO", null, false, "TX-5", NOW.minusSeconds(300_000));
        refundCase("NO_CHARGE", null, "AUTO", null, false, null, NOW.minusSeconds(300_000));
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("refundOwner=PERSON이면 사람 담당 중(담당 종료 전) 대상만 오래 열린 순으로 주고 카드·사용자·거래 키 값 없이 돈의 상태·담당·기한을 준다")
    void listsPersonOpenRefundCasesOldestFirst() throws Exception {
        String body = body(get(PATH).param("refundOwner", "PERSON"));
        JsonNode data = objectMapper.readTree(body).get("data");

        assertThat(orderIds(data)).containsExactly(personOld, personMiddle, personNew);
        JsonNode newest = data.get(2);
        assertThat(fieldNames(newest)).containsExactlyInAnyOrder("orderId", "providerOrderId", "providerId", "amount",
            "moneyStatus", "refundActor", "owner", "ownerReason", "ownerChangedAt", "ownerClosedAt", "priorProviderStatus",
            "hasTransactionKey", "openedAt", "elapsedSeconds", "handoffAt", "promiseAt", "checkAttempts", "nextCheckAt",
            "refundRequestedAt", "concludedAt", "lastCheckAt", "lastCheckOutcome", "lastCheckCause");
        assertThat(newest.get("providerOrderId").asText()).isEqualTo("PO-" + personNew);
        assertThat(newest.get("providerId").asText()).isEqualTo("test-pg");
        assertThat(newest.get("amount").asLong()).isEqualTo(1000);
        assertThat(newest.get("moneyStatus").asText()).isEqualTo("CHARGED");
        assertThat(newest.get("refundActor").isNull()).isTrue();
        assertThat(newest.get("owner").asText()).isEqualTo("PERSON");
        assertThat(newest.get("ownerReason").asText()).isEqualTo("TRANSACTION_MISMATCH");
        assertThat(newest.get("ownerChangedAt").asText()).isEqualTo("2026-10-20T23:30:00Z");
        assertThat(newest.get("ownerClosedAt").isNull()).isTrue();
        assertThat(newest.get("priorProviderStatus").asText()).isEqualTo("PENDING");
        assertThat(newest.get("hasTransactionKey").asBoolean()).isTrue();
        assertThat(newest.get("openedAt").asText()).isEqualTo("2026-10-20T23:00:00Z");
        assertThat(newest.get("elapsedSeconds").asLong()).isEqualTo(3_600);
        assertThat(newest.get("handoffAt").asText()).isEqualTo("2026-10-22T23:00:00Z");
        assertThat(newest.get("promiseAt").asText()).isEqualTo("2026-10-24T23:00:00Z");
        assertThat(newest.get("checkAttempts").asInt()).isEqualTo(3);
        assertThat(newest.get("nextCheckAt").asText()).isEqualTo("2026-10-21T00:30:00Z");
        assertThat(newest.get("refundRequestedAt").isNull()).isTrue();
        assertThat(newest.get("concludedAt").isNull()).isTrue();
        assertThat(data.get(0).get("hasTransactionKey").asBoolean()).isFalse();
        assertThat(data.get(0).get("moneyStatus").asText()).isEqualTo("CHECKING");
        assertThat(body).doesNotContain("userId", "card", SECRET_KEY);
    }

    @Test
    @DisplayName("refundMoneyStatus=REFUND_UNKNOWN이면 결과 모름 대상만 주고 여러 값을 주면 그 돈의 상태 대상을 담당과 무관하게 오래 열린 순으로 주며 refundOwner와 함께 주면 둘 다 맞는 대상만 준다")
    void listsByMoneyStatusAloneOrWithOwner() throws Exception {
        assertThat(orderIds(list(get(PATH).param("refundMoneyStatus", "REFUND_UNKNOWN")))).containsExactly(refundUnknown);
        assertThat(orderIds(list(get(PATH).param("refundMoneyStatus", "CHARGED", "REFUND_UNKNOWN"))))
            .containsExactly(personMiddle, refundUnknown, personNew);
        assertThat(orderIds(list(get(PATH).param("refundMoneyStatus", "CHECKING")))).containsExactly(autoChecking, personOld);
        assertThat(orderIds(list(get(PATH).param("refundOwner", "PERSON").param("refundMoneyStatus", "CHARGED"))))
            .containsExactly(personMiddle, personNew);
    }

    @Test
    @DisplayName("환불 확인 대상도 page·size를 오래 열린 순 정렬 위에서 자른다")
    void pagesRefundCasesOverOldestFirstOrder() throws Exception {
        assertThat(orderIds(list(get(PATH).param("refundOwner", "PERSON").param("page", "1").param("size", "2"))))
            .containsExactly(personNew);
        assertThat(orderIds(list(get(PATH).param("refundOwner", "PERSON").param("page", "2").param("size", "2"))))
            .isEmpty();
    }

    @Test
    @DisplayName("환불 확인 대상의 경과 시간 필터는 열린 시각 기준이고 경과 시간이 기준과 같으면 포함한다")
    void filtersRefundCasesByOpenedAtAge() throws Exception {
        assertThat(orderIds(list(get(PATH).param("refundMoneyStatus", "CHARGED", "REFUND_UNKNOWN")
            .param("minAgeSeconds", "7200")))).containsExactly(personMiddle, refundUnknown);
        assertThat(orderIds(list(get(PATH).param("refundOwner", "PERSON").param("minAgeSeconds", "86401"))))
            .containsExactly(personOld);
    }

    @Test
    @DisplayName("결제 대기 상태 필터와 환불 확인 필터를 한 요청에 섞으면 원천이 달라 400이다")
    void rejectsMixedSources() throws Exception {
        mvc.perform(get(PATH).param("status", "PENDING").param("refundOwner", "PERSON").with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("status", "PENDING").param("refundMoneyStatus", "REFUND_UNKNOWN")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("환불 확인 대상이 있어도 환불 필터 없이 status가 없거나 기존 값이면 결제 대기 PG 결제만 기존 항목 모양으로 준다")
    void keepsExistingStatusesOnPendingPayments() throws Exception {
        JsonNode all = list(get(PATH));
        JsonNode pendingOnly = list(get(PATH).param("status", "PENDING"));

        assertThat(orderIds(all)).containsExactly(pending);
        assertThat(orderIds(pendingOnly)).containsExactly(pending);
        assertThat(fieldNames(all.get(0))).containsExactlyInAnyOrder("orderId", "providerOrderId", "providerId",
            "providerStatus", "startedAt", "elapsedSeconds", "lookupAttempts", "nextLookupAt", "latestAnomaly");
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"status, HANDED_TO_HUMAN", "status, REFUND_UNKNOWN", "refundMoneyStatus, REFUNDED",
        "refundMoneyStatus, NO_CHARGE", "refundMoneyStatus, refund_unknown", "refundMoneyStatus, BOGUS",
        "refundOwner, AUTO", "refundOwner, person", "refundOwner, BOGUS"})
    @DisplayName("옛 환불 status 값, 끝난 돈의 상태나 모르는 돈의 상태, PERSON이 아닌 담당은 400이다")
    void rejectsOtherRefundFilters(String name, String value) throws Exception {
        mvc.perform(get(PATH).param(name, value).with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("관리자가 아니거나 식별되지 않은 요청은 환불 필터를 줘도 유효한 CSRF가 있어도 403이다")
    void rejectsNonAdminForRefundFilters() throws Exception {
        mvc.perform(get(PATH).param("refundOwner", "PERSON").with(csrf()).with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(get(PATH).param("refundOwner", "PERSON").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("C9: 환불 확인 대상 항목에 마지막 확인 시각·분류(대문자 enum 이름)·원인을 주고 확인한 적 없는 대상은 세 칸이 null이다")
    void listsLastCheckColumns() throws Exception {
        jdbc.update("update payment_refund_cases set last_check_at = ?, last_check_outcome = 'APPLY_FAILED',"
            + " last_check_cause = 'IllegalStateException' where order_id = ?", utc(NOW.minusSeconds(60)), personNew);

        JsonNode data = list(get(PATH).param("refundOwner", "PERSON"));

        JsonNode newest = data.get(2);
        assertThat(newest.get("orderId").asLong()).isEqualTo(personNew);
        assertThat(newest.get("lastCheckAt").asText()).isEqualTo("2026-10-20T23:59:00Z");
        assertThat(newest.get("lastCheckOutcome").asText()).isEqualTo("APPLY_FAILED");
        assertThat(newest.get("lastCheckCause").asText()).isEqualTo("IllegalStateException");
        assertThat(data.get(0).get("lastCheckAt").isNull()).isTrue();
        assertThat(data.get(0).get("lastCheckOutcome").isNull()).isTrue();
        assertThat(data.get(0).get("lastCheckCause").isNull()).isTrue();
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

    // 만료 주문과 그 결제 기록, 환불 확인 대상을 함께 만든다. 사람 담당은 열린 지 30분 뒤에 바뀐 것으로, 돈이 끝난 행은 결론 시각을
    // 열린 지 30분 뒤로 둔다. ownerClosed면 그 결론 시각에 SYSTEM이 담당을 닫은 것으로 둔다.
    private long refundCase(String moneyStatus, String refundActor, String owner, String ownerReason, boolean ownerClosed,
        String transactionKey, Instant openedAt) {
        long orderId = pgOrder("PAYMENT_EXPIRED", "EXPIRED", "EXPIRED", transactionKey, openedAt);
        boolean concluded = List.of("REFUNDED", "NO_CHARGE").contains(moneyStatus);
        boolean person = "PERSON".equals(owner);
        LocalDateTime concludedAt = concluded ? utc(openedAt.plusSeconds(1_800)) : null;
        jdbc.update("insert into payment_refund_cases(order_id, provider_id, provider_order_id, amount, money_status, "
                + "refund_actor, owner, owner_reason, owner_changed_at, owner_closed_at, owner_closed_by, prior_provider_status, "
                + "transaction_key, opened_at, handoff_at, promise_at, next_check_at, check_attempts, refund_requested_at, "
                + "concluded_at) values (?, 'test-pg', ?, 1000, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, 3, ?, ?)",
            orderId, "PO-" + orderId, moneyStatus, refundActor, owner, ownerReason,
            person ? utc(openedAt.plusSeconds(1_800)) : null, ownerClosed ? concludedAt : null, ownerClosed ? "SYSTEM" : null,
            transactionKey, utc(openedAt), utc(openedAt.plusSeconds(2 * 86_400)), utc(openedAt.plusSeconds(4 * 86_400)),
            concluded ? null : utc(NOW.plusSeconds(1_800)),
            "REFUND_UNKNOWN".equals(moneyStatus) || "REFUND_REQUESTED".equals(moneyStatus) ? utc(openedAt.plusSeconds(60)) : null,
            concludedAt);
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
