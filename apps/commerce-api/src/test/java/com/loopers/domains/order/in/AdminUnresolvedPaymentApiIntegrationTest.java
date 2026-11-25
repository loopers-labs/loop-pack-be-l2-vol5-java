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

// 미확인 결제 목록 대상: 주문이 PAYMENT_PENDING인 PG 결제(send_started·pending·unknown·needs_review)다. 종결 주문과 포인트 결제는 넣지 않는다.
// 시작 시각을 삽입 순서와 다르게 둬 정렬 기준이 ID가 아니라 시작 시각임을 드러낸다. 종결 주문을 가장 오래된 시각에 둬 대상 조건이 빠지면 첫 항목이 된다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentVerificationApiIntegrationTest.FixedClockConfig.class)
class AdminUnresolvedPaymentApiIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final String PATH = "/api-admin/v1/payments/unresolved";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    private long pending;
    private long sendStarted;
    private long unknown;
    private long needsReview;

    @BeforeEach
    void prepare() {
        pending = pgOrder("PAYMENT_PENDING", "PENDING", "PENDING", NOW.minusSeconds(300));
        sendStarted = pgOrder("PAYMENT_PENDING", "PENDING", "SEND_STARTED", NOW.minusSeconds(30));
        unknown = pgOrder("PAYMENT_PENDING", "PENDING", "UNKNOWN", NOW.minusSeconds(600));
        needsReview = pgOrder("PAYMENT_PENDING", "PENDING", "NEEDS_REVIEW", NOW.minusSeconds(120));
        pgOrder("CONFIRMED", "SUCCESS", "SUCCESS", NOW.minusSeconds(900));
        pgOrder("PAYMENT_FAILED", "FAILED", "FAILED", NOW.minusSeconds(800));
        pgOrder("PAYMENT_EXPIRED", "EXPIRED", "EXPIRED", NOW.minusSeconds(700));
        pointOrder("PAYMENT_PENDING");
        pointOrder("CONFIRMED");
        schedule(pending, 2, NOW.plusSeconds(4));
        schedule(unknown, 5, NOW.plusSeconds(20));
        anomaly(unknown, "LOOKUP_FAILED", "lookupFailed=true", NOW.minusSeconds(10));
        anomaly(needsReview, "LOOKUP_FAILED", "lookupFailed=true", NOW.minusSeconds(200));
        anomaly(needsReview, "TRANSACTION_MISMATCH", "orderMatches=true, amountMatches=false, keyMatches=true",
            NOW.minusSeconds(60));
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("관리자 목록은 결제 대기 PG 결제 네 상태만 오래된 순으로 주고 카드·사용자 정보 없이 조회 횟수·최근 이상을 함께 준다")
    void listsUnresolvedPgPaymentsOldestFirst() throws Exception {
        JsonNode data = list(get(PATH));

        assertThat(orderIds(data)).containsExactly(unknown, pending, needsReview, sendStarted);
        JsonNode oldest = data.get(0);
        assertThat(fieldNames(oldest)).containsExactlyInAnyOrder("orderId", "providerOrderId", "providerId", "providerStatus",
            "startedAt", "elapsedSeconds", "lookupAttempts", "nextLookupAt", "latestAnomaly");
        assertThat(oldest.get("providerOrderId").asText()).isEqualTo("PO-" + unknown);
        assertThat(oldest.get("providerId").asText()).isEqualTo("test-pg");
        assertThat(oldest.get("providerStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(oldest.get("startedAt").asText()).isEqualTo("2026-10-20T23:50:00Z");
        assertThat(oldest.get("elapsedSeconds").asLong()).isEqualTo(600);
        assertThat(oldest.get("lookupAttempts").asInt()).isEqualTo(5);
        assertThat(oldest.get("nextLookupAt").asText()).isEqualTo("2026-10-21T00:00:20Z");
        assertThat(fieldNames(oldest.get("latestAnomaly"))).containsExactlyInAnyOrder("kind", "summary", "lastSeenAt");
        assertThat(oldest.get("latestAnomaly").get("kind").asText()).isEqualTo("LOOKUP_FAILED");
        assertThat(oldest.get("latestAnomaly").get("summary").asText()).isEqualTo("lookupFailed=true");
        assertThat(oldest.get("latestAnomaly").get("lastSeenAt").asText()).isEqualTo("2026-10-20T23:59:50Z");
        assertThat(data.get(2).get("latestAnomaly").get("kind").asText()).isEqualTo("TRANSACTION_MISMATCH");
        JsonNode newest = data.get(3);
        assertThat(newest.get("providerStatus").asText()).isEqualTo("SEND_STARTED");
        assertThat(newest.get("lookupAttempts").isNull()).isTrue();
        assertThat(newest.get("nextLookupAt").isNull()).isTrue();
        assertThat(newest.get("latestAnomaly").isNull()).isTrue();
    }

    @Test
    @DisplayName("상태·경과 시간 필터는 둘 다 만족하는 결제만 남기고 경과 시간이 기준과 같으면 포함한다")
    void filtersByStatusAndMinimumAge() throws Exception {
        assertThat(orderIds(list(get(PATH).param("status", "UNKNOWN", "NEEDS_REVIEW"))))
            .containsExactly(unknown, needsReview);
        assertThat(orderIds(list(get(PATH).param("minAgeSeconds", "300")))).containsExactly(unknown, pending);
        assertThat(orderIds(list(get(PATH).param("status", "PENDING", "SEND_STARTED").param("minAgeSeconds", "60"))))
            .containsExactly(pending);
    }

    @Test
    @DisplayName("page·size는 오래된 순 정렬 위에서 자른다")
    void pagesOverOldestFirstOrder() throws Exception {
        assertThat(orderIds(list(get(PATH).param("page", "1").param("size", "2")))).containsExactly(needsReview, sendStarted);
        assertThat(orderIds(list(get(PATH).param("page", "2").param("size", "2")))).isEmpty();
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
        "size, 101",
        "size, 0",
        "page, -1",
        "minAgeSeconds, -1",
        "status, CONFIRMED",
        "status, BOGUS"
    })
    @DisplayName("size 상한 초과·음수 page·음수 경과 시간·미확인이 아닌 상태는 400이다")
    void rejectsInvalidParameters(String name, String value) throws Exception {
        mvc.perform(get(PATH).param(name, value).with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("관리자가 아니거나 식별되지 않은 요청은 유효한 CSRF가 있어도 403이다")
    void rejectsNonAdmin() throws Exception {
        mvc.perform(get(PATH).with(csrf()).with(user("customer").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(csrf())).andExpect(status().isForbidden());
    }

    private JsonNode list(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
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

    private long pgOrder(String orderStatus, String result, String providerStatus, Instant startedAt) {
        long orderId = order(orderStatus);
        jdbc.update("insert into payment_records(order_id, method, amount, result, attempt_key, provider_id, provider_order_id, "
                + "provider_status, started_at) values (?, 'PG', 1000, ?, ?, 'test-pg', ?, ?, ?)",
            orderId, result, UUID.randomUUID().toString(), "PO-" + orderId, providerStatus, utc(startedAt));
        return orderId;
    }

    private void pointOrder(String orderStatus) {
        long orderId = order(orderStatus);
        jdbc.update("insert into payment_records(order_id, method, amount, result) values (?, 'POINT', 1000, 'SUCCESS')", orderId);
    }

    private void schedule(long orderId, int attempts, Instant nextLookupAt) {
        jdbc.update("insert into payment_lookup_schedules(order_id, next_lookup_at, actual_attempts, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?)", orderId, utc(nextLookupAt), attempts, utc(NOW.minusSeconds(600)), utc(NOW));
    }

    private void anomaly(long orderId, String kind, String summary, Instant lastSeenAt) {
        jdbc.update("insert into payment_anomalies(order_id, kind, first_seen_at, last_seen_at, occurrences, last_summary)"
            + " values (?, ?, ?, ?, 1, ?)", orderId, kind, utc(lastSeenAt), utc(lastSeenAt), summary);
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

    // JPA는 시각을 UTC 벽시계로 저장한다(jdbc.time_zone UTC). JVM 기본 시간대(Asia/Seoul)를 타는 Timestamp 대신 UTC 벽시계 값을 넣는다.
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
