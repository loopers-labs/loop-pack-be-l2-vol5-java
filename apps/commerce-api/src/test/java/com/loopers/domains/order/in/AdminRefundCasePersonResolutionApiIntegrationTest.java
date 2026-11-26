package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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

// S5(10주차 5b 계획 5절): 사람 담당 중인 환불 확인 대상에 관리자가 PG로 확인되지 않는 결론을 적는다.
// 실제 Controller~테스트 DB·기본 보안 필터로 보고, 변경 요청의 권한 거절은 유효한 CSRF를 붙여 CSRF 거절과 구분한다.
// 대상 행은 서비스 경로 대신 SQL로 둔다(돈·담당 조합을 정확히 고르기 위해서). counter는 공유 MeterRegistry라 증가분으로 본다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentVerificationApiIntegrationTest.FixedClockConfig.class)
class AdminRefundCasePersonResolutionApiIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant OPENED_AT = NOW.minusSeconds(200_000);
    private static final Instant OWNER_CHANGED_AT = NOW.minusSeconds(3_600);
    private static final String OUTCOME_METRIC = "commerce.payment.refund_outcome";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() {
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("사람 담당 중인 청구 확인 대상에 관리자가 환불함을 적으면 200이고 환불됨(PERSON)·담당 종료(관리자 이름·지금)가 저장되며 refunded/person counter가 1 는다")
    void recordsRefundedByPerson() throws Exception {
        long orderId = refundCase("CHARGED", null, "PERSON", "RESULT_REVERSED", null, null);
        double before = outcome("refunded", "person");

        JsonNode data = data(admin(orderId, "{\"result\":\"REFUNDED\"}").andExpect(status().isOk()));

        assertThat(data.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(data.get("moneyStatus").asText()).isEqualTo("REFUNDED");
        assertThat(data.get("refundActor").asText()).isEqualTo("PERSON");
        assertThat(data.get("owner").asText()).isEqualTo("PERSON");
        assertThat(data.get("ownerClosedAt").asText()).isEqualTo("2026-10-21T00:00:00Z");
        assertThat(data.get("ownerClosedBy").asText()).isEqualTo("admin");
        Map<String, Object> row = row(orderId);
        assertThat(row.get("money_status")).isEqualTo("REFUNDED");
        assertThat(row.get("refund_actor")).isEqualTo("PERSON");
        assertThat(row.get("owner")).isEqualTo("PERSON");
        assertThat(row.get("owner_reason")).isEqualTo("RESULT_REVERSED");
        assertThat(row.get("owner_closed_by")).isEqualTo("admin");
        assertThat(time(orderId, "owner_closed_at")).isEqualTo(utc(NOW));
        assertThat(time(orderId, "concluded_at")).isEqualTo(utc(NOW));
        assertThat(time(orderId, "next_check_at")).isNull();
        assertThat(outcome("refunded", "person") - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("기한으로 사람 담당이 된 확인 중 대상에 관리자가 청구 없음을 적으면 200이고 청구 없음·환불 주체 없음·담당 종료가 저장되며 no_charge/person counter가 1 는다")
    void recordsNoChargeByPerson() throws Exception {
        long orderId = refundCase("CHECKING", null, "PERSON", "HANDOFF_DEADLINE", null, null);
        double before = outcome("no_charge", "person");

        JsonNode data = data(admin(orderId, "{\"result\":\"NO_CHARGE\"}").andExpect(status().isOk()));

        assertThat(data.get("moneyStatus").asText()).isEqualTo("NO_CHARGE");
        assertThat(data.get("refundActor").isNull()).isTrue();
        Map<String, Object> row = row(orderId);
        assertThat(row.get("money_status")).isEqualTo("NO_CHARGE");
        assertThat(row.get("refund_actor")).isNull();
        assertThat(row.get("owner_closed_by")).isEqualTo("admin");
        assertThat(time(orderId, "owner_closed_at")).isEqualTo(utc(NOW));
        assertThat(time(orderId, "concluded_at")).isEqualTo(utc(NOW));
        assertThat(outcome("no_charge", "person") - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("자동 담당 대상에 사람 기록을 보내면 409이고 돈·담당이 그대로이며 counter가 늘지 않는다")
    void rejectsAutoOwner() throws Exception {
        long orderId = refundCase("CHECKING", null, "AUTO", null, null, null);
        double before = outcome("refunded", "person");

        admin(orderId, "{\"result\":\"REFUNDED\"}").andExpect(status().isConflict());

        assertThat(state(orderId)).isEqualTo("CHECKING/AUTO/-/-");
        assertThat(outcome("refunded", "person") - before).isZero();
    }

    @Test
    @DisplayName("PG 사실로 돈이 끝나 담당이 이미 닫힌 대상에 사람 기록을 보내면 409이고 처음 결론·종료 주체가 그대로다")
    void rejectsClosedOwner() throws Exception {
        long orderId = refundCase("REFUNDED", "EXTERNAL", "PERSON", "HANDOFF_DEADLINE", utc(OWNER_CHANGED_AT), "SYSTEM");
        double before = outcome("no_charge", "person");

        admin(orderId, "{\"result\":\"NO_CHARGE\"}").andExpect(status().isConflict());

        assertThat(state(orderId)).isEqualTo("REFUNDED/PERSON/EXTERNAL/SYSTEM");
        assertThat(outcome("no_charge", "person") - before).isZero();
    }

    @Test
    @DisplayName("담당이 열린 채 돈만 끝난 모순 행에 사람 기록을 보내면 돈이 이미 끝나 409이고 그대로다")
    void rejectsConcludedMoney() throws Exception {
        long orderId = refundCase("NO_CHARGE", null, "PERSON", "HANDOFF_DEADLINE", null, null);

        admin(orderId, "{\"result\":\"REFUNDED\"}").andExpect(status().isConflict());

        assertThat(state(orderId)).isEqualTo("NO_CHARGE/PERSON/-/-");
    }

    @Test
    @DisplayName("환불 확인 대상이 없는 주문이면 404다")
    void rejectsMissingCase() throws Exception {
        admin(999_999L, "{\"result\":\"REFUNDED\"}").andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "본문={0}")
    @ValueSource(strings = {"{\"result\":\"BOGUS\"}", "{\"result\":\"refunded\"}", "{\"result\":\"CHARGED\"}",
        "{\"result\":1}", "{\"result\":null}", "{}"})
    @DisplayName("result가 REFUNDED·NO_CHARGE 문자열이 아니면 400이고 대상이 그대로다")
    void rejectsMalformedResult(String body) throws Exception {
        long orderId = refundCase("CHARGED", null, "PERSON", "RESULT_REVERSED", null, null);

        admin(orderId, body).andExpect(status().isBadRequest());

        assertThat(state(orderId)).isEqualTo("CHARGED/PERSON/-/-");
    }

    @Test
    @DisplayName("관리자가 아니거나 식별되지 않은 요청은 유효한 CSRF가 있어도 403이고 대상이 그대로다")
    void rejectsNonAdmin() throws Exception {
        long orderId = refundCase("CHARGED", null, "PERSON", "RESULT_REVERSED", null, null);
        String body = "{\"result\":\"REFUNDED\"}";

        mvc.perform(post(path(orderId)).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(csrf()).with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(post(path(orderId)).contentType(MediaType.APPLICATION_JSON).content(body).with(csrf()))
            .andExpect(status().isForbidden());

        assertThat(state(orderId)).isEqualTo("CHARGED/PERSON/-/-");
    }

    @Test
    @DisplayName("관리자여도 CSRF 토큰이 없으면 403이고 대상이 그대로다")
    void rejectsMissingCsrf() throws Exception {
        long orderId = refundCase("CHARGED", null, "PERSON", "RESULT_REVERSED", null, null);

        mvc.perform(post(path(orderId)).contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"REFUNDED\"}")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isForbidden());

        assertThat(state(orderId)).isEqualTo("CHARGED/PERSON/-/-");
    }

    private ResultActions admin(long orderId, String body) throws Exception {
        return mvc.perform(post(path(orderId)).contentType(MediaType.APPLICATION_JSON).content(body)
            .with(csrf()).with(user("admin").roles("ADMIN")));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static String path(long orderId) {
        return "/api-admin/v1/payments/refund-cases/" + orderId + "/person-resolution";
    }

    private Map<String, Object> row(long orderId) {
        return jdbc.queryForMap("select * from payment_refund_cases where order_id = ?", orderId);
    }

    private LocalDateTime time(long orderId, String column) {
        return jdbc.queryForObject("select " + column + " from payment_refund_cases where order_id = ?", LocalDateTime.class,
            orderId);
    }

    private String state(long orderId) {
        return jdbc.queryForObject("select concat(money_status, '/', owner, '/', coalesce(refund_actor, '-'), '/', "
            + "coalesce(owner_closed_by, '-')) from payment_refund_cases where order_id = ?", String.class, orderId);
    }

    private double outcome(String result, String actor) {
        Counter counter = meterRegistry.find(OUTCOME_METRIC).tag("result", result).tag("actor", actor).counter();
        return counter == null ? 0 : counter.count();
    }

    // 만료 주문과 그 결제 기록, 환불 확인 대상을 함께 만든다. 돈이 끝난 행은 결론 시각을 열린 지 30분 뒤로 둔다.
    private long refundCase(String moneyStatus, String refundActor, String owner, String ownerReason,
        LocalDateTime ownerClosedAt, String ownerClosedBy) {
        long orderId = order();
        jdbc.update("insert into payment_records(order_id, method, amount, result, attempt_key, provider_id, provider_order_id, "
                + "transaction_key, provider_status, started_at) values (?, 'PG', 1000, 'EXPIRED', ?, 'test-pg', ?, 'TX-1', "
                + "'EXPIRED', ?)",
            orderId, UUID.randomUUID().toString(), "PO-" + orderId, utc(OPENED_AT));
        boolean concluded = "REFUNDED".equals(moneyStatus) || "NO_CHARGE".equals(moneyStatus);
        boolean person = "PERSON".equals(owner);
        jdbc.update("insert into payment_refund_cases(order_id, provider_id, provider_order_id, amount, money_status, "
                + "refund_actor, owner, owner_reason, owner_changed_at, owner_closed_at, owner_closed_by, prior_provider_status, "
                + "transaction_key, opened_at, handoff_at, promise_at, next_check_at, check_attempts, refund_requested_at, "
                + "concluded_at) values (?, 'test-pg', ?, 1000, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 'TX-1', ?, ?, ?, ?, 3, null, ?)",
            orderId, "PO-" + orderId, moneyStatus, refundActor, owner, ownerReason, person ? utc(OWNER_CHANGED_AT) : null,
            ownerClosedAt, ownerClosedBy, utc(OPENED_AT), utc(OPENED_AT.plusSeconds(2 * 86_400)),
            utc(OPENED_AT.plusSeconds(4 * 86_400)), concluded ? null : utc(NOW.plusSeconds(1_800)),
            concluded ? utc(OPENED_AT.plusSeconds(1_800)) : null);
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
