package com.loopers.interfaces.api.point;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.user.PointService;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointApiE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PointService pointService;

    @Autowired
    private FixtureUserInitializer initializer;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        initializer.initialize();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("API-07/C07·C08: 2000에 3000을 두 번 충전하면 응답·조회·DB가 5000, 8000이다.")
    @Test
    void chargesEverySuccessfulRequestAndReadsBalance() {
        pointService.charge("alice", 2000L);

        assertBalance(charge("alice", "{\"amount\":3000}"), 5000L);
        assertBalance(charge("alice", "{\"amount\":3000}"), 8000L);
        assertBalance(balance("alice"), 8000L);
        assertBalance(balance("bob"), 0L);
        assertThat(jdbcTemplate.queryForObject("SELECT point_balance FROM `user` WHERE id = 1", Long.class))
            .isEqualTo(8000L);
    }

    @DisplayName("API-08/C07: 누락·null·문자열·소수·0·음수·범위 초과·추가 필드 충전은 400이며 저장값을 보존한다.")
    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"amount\":null}", "{\"amount\":\"3000\"}", "{\"amount\":1.0}",
        "{\"amount\":1.5}", "{\"amount\":0}", "{\"amount\":-1}",
        "{\"amount\":9223372036854775808}", "{\"amount\":-9223372036854775809}",
        "{\"amount\":true}", "{\"amount\":[]}", "{\"amount\":3000,\"extra\":1}",
        "[]", "null", "{", ""
    })
    void rejectsInvalidChargeWithoutChangingStoredUser(String body) {
        pointService.charge("alice", 2000L);
        var before = jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id");

        assertFailure(charge("alice", body), HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");

        assertThat(jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id")).isEqualTo(before);
    }

    @DisplayName("API-08/C07: 잔액 1에 Long 최댓값을 충전하면 409이고 잔액 1을 보존한다.")
    @Test
    void rejectsBalanceOverflowWithoutChangingStoredUser() {
        pointService.charge("alice", 1L);
        var before = jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id");

        assertFailure(charge("alice", "{\"amount\":9223372036854775807}"), HttpStatus.CONFLICT,
            "POINT_BALANCE_LIMIT_EXCEEDED", "포인트 잔액의 허용 범위를 초과합니다.");

        assertThat(jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id")).isEqualTo(before);
        assertBalance(balance("alice"), 1L);
    }

    @DisplayName("API-07/C07·C08: Long 최댓값 충전과 조회는 정수 그대로 응답한다.")
    @Test
    void acceptsMaximumBalanceWithoutPrecisionLoss() {
        assertBalance(charge("alice", "{\"amount\":9223372036854775807}"), Long.MAX_VALUE);
        assertBalance(balance("alice"), Long.MAX_VALUE);
    }

    @DisplayName("C07·C08: 미등록·원문 불일치 사용자에게 404를 반환하고 저장값을 보존한다.")
    @ParameterizedTest
    @ValueSource(strings = {"unknown", "Alice", "1"})
    void rejectsUnregisteredUser(String requester) {
        var before = jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id");

        assertFailure(charge(requester, "{\"amount\":3000}"), HttpStatus.NOT_FOUND,
            "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");
        assertFailure(balance(requester), HttpStatus.NOT_FOUND,
            "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");

        assertThat(jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id")).isEqualTo(before);
    }

    @DisplayName("C07·C08: 사용자 헤더 누락·빈 값·공백은 400이고 저장값을 보존한다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingUserHeader(String requester) {
        var before = jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id");

        assertFailure(charge(requester, "{\"amount\":3000}"), HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
        assertFailure(balance(requester), HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");

        assertThat(jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id")).isEqualTo(before);
    }

    @DisplayName("C07: 고객 요청은 사용자 조회보다 잘못된 amount 형식을 우선 거절한다.")
    @ParameterizedTest
    @ValueSource(strings = {"{\"amount\":0}", "{\"amount\":-1}", "{\"amount\":\"3000\"}", "{}"})
    void validatesAmountBeforeResolvingRequester(String body) {
        assertFailure(charge("unknown", body), HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
    }

    @DisplayName("C07·C08: 매핑된 사용자 DB 행이 없으면 404이고 요청에서 자동 생성하지 않는다.")
    @Test
    void doesNotCreateMissingUserOnRequest() {
        jdbcTemplate.update("DELETE FROM `user` WHERE id = 1");

        assertFailure(charge("alice", "{\"amount\":3000}"), HttpStatus.NOT_FOUND,
            "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");
        assertFailure(balance("alice"), HttpStatus.NOT_FOUND,
            "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `user` WHERE id = 1", Long.class)).isZero();
    }

    private ResponseEntity<JsonNode> charge(String requester, String body) {
        return restTemplate.exchange("/api/v1/points/charge", HttpMethod.POST,
            new HttpEntity<>(body, headers(requester)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> balance(String requester) {
        return restTemplate.exchange("/api/v1/points", HttpMethod.GET,
            new HttpEntity<>(headers(requester)), JsonNode.class);
    }

    private HttpHeaders headers(String requester) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requester != null) {
            headers.set("X-USER-ID", requester);
        }
        return headers;
    }

    private void assertBalance(ResponseEntity<JsonNode> response, long expectedBalance) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertAll(
            () -> assertThat(body.size()).isEqualTo(2),
            () -> assertThat(body.path("meta").size()).isEqualTo(1),
            () -> assertThat(body.path("meta").path("result").asText()).isEqualTo("SUCCESS"),
            () -> assertThat(body.path("data").size()).isEqualTo(1),
            () -> assertThat(body.path("data").path("balance").isIntegralNumber()).isTrue(),
            () -> assertThat(body.path("data").path("balance").longValue()).isEqualTo(expectedBalance)
        );
    }

    private void assertFailure(ResponseEntity<JsonNode> response, HttpStatus status, String code, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertAll(
            () -> assertThat(body.size()).isEqualTo(1),
            () -> assertThat(body.path("meta").size()).isEqualTo(3),
            () -> assertThat(body.path("meta").path("result").asText()).isEqualTo("FAIL"),
            () -> assertThat(body.path("meta").path("errorCode").asText()).isEqualTo(code),
            () -> assertThat(body.path("meta").path("message").asText()).isEqualTo(message),
            () -> assertThat(body.has("data")).isFalse()
        );
    }
}
