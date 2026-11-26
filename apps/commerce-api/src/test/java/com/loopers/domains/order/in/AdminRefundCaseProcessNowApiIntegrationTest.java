package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 10주차 5c "지금 처리"(C3·C7): 사람 담당 중인 환불 확인 대상 하나에 스케줄러와 같은 회차를 바로 돌린다.
// 실제 Controller~테스트 DB·test-pg·기본 보안 필터로 보고, 권한 거절은 유효한 CSRF를 붙여 CSRF 거절과 구분한다.
// 대상 행은 돈·담당 조합을 정확히 고르려고 SQL로 둔다. 다음 조회 시각은 지금보다 뒤로 둬 버튼이 기한과 무관하게 도는지 본다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentVerificationApiIntegrationTest.FixedClockConfig.class)
class AdminRefundCaseProcessNowApiIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant OPENED_AT = NOW.minusSeconds(200_000);
    private static final Instant OWNER_CHANGED_AT = NOW.minusSeconds(3_600);
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestPgFixture pg;
    @Autowired private DatabaseCleanUp cleanup;
    private TestPgServer server;

    @BeforeEach
    void prepare() {
        server = pg.start();
    }

    @AfterEach
    void clean() throws Exception {
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("C3: 청구 확인·사람 담당 중 대상에 관리자가 지금 처리를 누르면 200이고 취소를 한 번 보내 환불됨(SYSTEM)·담당 종료(관리자 이름)·마지막 확인 OK가 저장되고 응답에 그대로 나온다")
    void refundsChargedPersonCase() throws Exception {
        long orderId = refundCase("CHARGED", "PERSON", null, null, true);

        JsonNode data = data(admin(orderId).andExpect(status().isOk()));

        assertThat(data.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(data.get("moneyStatus").asText()).isEqualTo("REFUNDED");
        assertThat(data.get("refundActor").asText()).isEqualTo("SYSTEM");
        assertThat(data.get("owner").asText()).isEqualTo("PERSON");
        assertThat(data.get("ownerClosedAt").asText()).isEqualTo("2026-10-21T00:00:00Z");
        assertThat(data.get("ownerClosedBy").asText()).isEqualTo("admin");
        assertThat(data.get("lastCheckAt").asText()).isEqualTo("2026-10-21T00:00:00Z");
        assertThat(data.get("lastCheckOutcome").asText()).isEqualTo("OK");
        assertThat(data.get("lastCheckCause").isNull()).isTrue();
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isEqualTo(1);
        Map<String, Object> row = row(orderId);
        assertThat(row.get("money_status")).isEqualTo("REFUNDED");
        assertThat(row.get("refund_actor")).isEqualTo("SYSTEM");
        assertThat(row.get("owner_reason")).isEqualTo("RESULT_REVERSED");
        assertThat(row.get("owner_closed_by")).isEqualTo("admin");
        assertThat(row.get("owner_closed_at")).isEqualTo(utc(NOW));
        assertThat(row.get("refund_requested_at")).isEqualTo(utc(NOW));
        assertThat(row.get("last_check_outcome")).isEqualTo("OK");
        assertThat(row.get("last_check_at")).isEqualTo(utc(NOW));
    }

    @Test
    @DisplayName("C7: 자동 담당 대상에 지금 처리를 누르면 409이고 PG를 부르지 않으며 대상이 그대로다")
    void rejectsAutoOwner() throws Exception {
        long orderId = refundCase("CHARGED", "AUTO", null, null, true);

        admin(orderId).andExpect(status().isConflict());

        assertThat(state(orderId)).isEqualTo("CHARGED/AUTO/-/-");
        assertThat(server.lookupCalls()).isZero();
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
    }

    @Test
    @DisplayName("C7: 돈이 끝나 담당이 닫힌 대상에 지금 처리를 누르면 409이고 처음 결론·종료 주체가 그대로다")
    void rejectsConcludedCase() throws Exception {
        long orderId = refundCase("REFUNDED", "PERSON", utc(OWNER_CHANGED_AT), "SYSTEM", true);

        admin(orderId).andExpect(status().isConflict());

        assertThat(state(orderId)).isEqualTo("REFUNDED/PERSON/-/SYSTEM");
        assertThat(server.lookupCalls()).isZero();
    }

    @Test
    @DisplayName("C7: 환불 확인 대상이 없는 주문이면 404다")
    void rejectsMissingCase() throws Exception {
        admin(999_999L).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("C7: 관리자가 아니거나 식별되지 않은 요청은 유효한 CSRF가 있어도 403이고 PG를 부르지 않으며 대상이 그대로다")
    void rejectsNonAdmin() throws Exception {
        long orderId = refundCase("CHARGED", "PERSON", null, null, true);

        mvc.perform(post(path(orderId)).with(csrf()).with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(post(path(orderId)).with(csrf()))
            .andExpect(status().isForbidden());

        assertThat(state(orderId)).isEqualTo("CHARGED/PERSON/-/-");
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
    }

    @Test
    @DisplayName("C7: 관리자여도 CSRF 토큰이 없으면 403이고 PG를 부르지 않으며 대상이 그대로다")
    void rejectsMissingCsrf() throws Exception {
        long orderId = refundCase("CHARGED", "PERSON", null, null, true);

        mvc.perform(post(path(orderId)).with(user("admin").roles("ADMIN")))
            .andExpect(status().isForbidden());

        assertThat(state(orderId)).isEqualTo("CHARGED/PERSON/-/-");
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
    }

    private ResultActions admin(long orderId) throws Exception {
        return mvc.perform(post(path(orderId)).with(csrf()).with(user("admin").roles("ADMIN")));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static String path(long orderId) {
        return "/api-admin/v1/payments/refund-cases/" + orderId + "/process-now";
    }

    private static String providerOrderId(long orderId) {
        return "PO-" + orderId;
    }

    private Map<String, Object> row(long orderId) {
        return jdbc.queryForMap("select * from payment_refund_cases where order_id = ?", orderId);
    }

    private String state(long orderId) {
        return jdbc.queryForObject("select concat(money_status, '/', owner, '/', coalesce(refund_actor, '-'), '/', "
            + "coalesce(owner_closed_by, '-')) from payment_refund_cases where order_id = ?", String.class, orderId);
    }

    // 만료 주문과 그 결제 기록, 환불 확인 대상을 함께 만든다. charged면 test-pg에 같은 주문의 SUCCESS 거래를 두고 그 키를 남긴다.
    // 사람 담당은 결과 뒤집힘으로 열린 것으로 두고, 돈이 끝난 행은 결론 시각을 열린 지 30분 뒤로 둔다.
    private long refundCase(String moneyStatus, String owner, LocalDateTime ownerClosedAt, String ownerClosedBy,
        boolean charged) {
        long orderId = order();
        String transactionKey = charged ? server.addTransaction(providerOrderId(orderId), 1000, "SUCCESS") : null;
        jdbc.update("insert into payment_records(order_id, method, amount, result, attempt_key, provider_id, provider_order_id, "
                + "transaction_key, provider_status, started_at) values (?, 'PG', 1000, 'EXPIRED', ?, ?, ?, ?, 'EXPIRED', ?)",
            orderId, UUID.randomUUID().toString(), TestPgFixture.PROVIDER_ID, providerOrderId(orderId), transactionKey,
            utc(OPENED_AT));
        boolean concluded = "REFUNDED".equals(moneyStatus) || "NO_CHARGE".equals(moneyStatus);
        boolean person = "PERSON".equals(owner);
        jdbc.update("insert into payment_refund_cases(order_id, provider_id, provider_order_id, amount, money_status, "
                + "refund_actor, owner, owner_reason, owner_changed_at, owner_closed_at, owner_closed_by, prior_provider_status, "
                + "transaction_key, opened_at, handoff_at, promise_at, next_check_at, check_attempts, refund_requested_at, "
                + "concluded_at) values (?, ?, ?, 1000, ?, null, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, 3, null, ?)",
            orderId, TestPgFixture.PROVIDER_ID, providerOrderId(orderId), moneyStatus, owner,
            person ? "RESULT_REVERSED" : null, person ? utc(OWNER_CHANGED_AT) : null, ownerClosedAt, ownerClosedBy,
            transactionKey, utc(OPENED_AT), utc(OPENED_AT.plusSeconds(2 * 86_400)), utc(OPENED_AT.plusSeconds(4 * 86_400)),
            concluded ? null : utc(NOW.plusSeconds(1_800)), concluded ? utc(OPENED_AT.plusSeconds(1_800)) : null);
        return orderId;
    }

    private long order() {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into orders(user_id, total, status) values (1, 1000, 'PAYMENT_EXPIRED')", Statement.RETURN_GENERATED_KEYS);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    // JPA는 시각을 UTC 벽시계로 저장한다(jdbc.time_zone UTC). JVM 기본 시간대를 타는 Timestamp 대신 UTC 벽시계 값을 넣는다.
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
