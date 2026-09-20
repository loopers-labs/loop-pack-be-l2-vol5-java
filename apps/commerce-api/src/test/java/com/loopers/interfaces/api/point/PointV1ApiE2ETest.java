package com.loopers.interfaces.api.point;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.user.UserModel;
import com.loopers.support.ApiTestClient;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 포인트 EP-07~09, EP-27~28 의 HTTP 계약. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointV1ApiE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ApiTestClient api;
    private UserModel user;
    private UserModel admin;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(restTemplate, objectMapper);
        user = fixtures.userWithBalance(1_000L);
        admin = fixtures.admin();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("EP-07/08/09 고객 포인트")
    class Customer {
        @DisplayName("충전 → 조회 → 환불 흐름. 응답은 PointBalance(userId, balance).")
        @Test
        void chargeGetRefund() {
            var charged = api.post("/api/v1/points/charge", user.getId(), Map.of("amount", 500)).assertSuccess(HttpStatus.OK);
            assertThat(charged.data().path("userId").asLong()).isEqualTo(user.getId());
            assertThat(charged.data().path("balance").asLong()).isEqualTo(1_500L);

            var balance = api.get("/api/v1/points", user.getId()).assertSuccess(HttpStatus.OK);
            assertThat(balance.data().path("balance").asLong()).isEqualTo(1_500L);

            var refunded = api.post("/api/v1/points/refund", user.getId(), Map.of("amount", 1_500)).assertSuccess(HttpStatus.OK);
            assertThat(refunded.data().path("balance").asLong()).isZero();
        }

        @DisplayName("[ER-09 INVALID_AMOUNT] amount 누락·타입 오류·0 이하·표현 범위 초과 모두 400.")
        @Test
        void invalidAmount() {
            api.post("/api/v1/points/charge", user.getId(), Map.of()).assertError(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
            api.post("/api/v1/points/charge", user.getId(), Map.of("amount", "abc")).assertError(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
            api.post("/api/v1/points/charge", user.getId(), Map.of("amount", 0)).assertError(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
            api.post("/api/v1/points/charge", user.getId(), "{\"amount\": 99999999999999999999}").assertError(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
            api.post("/api/v1/points/refund", user.getId(), Map.of("amount", -1)).assertError(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");

            assertThat(fixtures.balanceOf(user.getId())).isEqualTo(1_000L);
        }

        @DisplayName("[ER-10 BALANCE_LIMIT_EXCEEDED] 잔액 + amount 가 표현 범위 초과면 409.")
        @Test
        void balanceLimitExceeded() {
            UserModel rich = fixtures.userWithBalance(Long.MAX_VALUE);

            api.post("/api/v1/points/charge", rich.getId(), Map.of("amount", 1)).assertError(HttpStatus.CONFLICT, "BALANCE_LIMIT_EXCEEDED");
        }

        @DisplayName("[ER-11 INSUFFICIENT_POINT] 잔액 < amount 환불은 409.")
        @Test
        void insufficientPoint() {
            api.post("/api/v1/points/refund", user.getId(), Map.of("amount", 1_001)).assertError(HttpStatus.CONFLICT, "INSUFFICIENT_POINT");
        }

        @DisplayName("[ER-01 USER_NOT_FOUND] 잔액 조회에 요청자가 없으면 404.")
        @Test
        void userNotFound() {
            api.get("/api/v1/points", 999_999L).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("EP-27/28 관리자 포인트")
    class Admin {
        @DisplayName("운영자 충전·차감은 대상 사용자의 잔액을 바꾸고 PointBalance 를 돌려준다.")
        @Test
        void chargeAndDeduct() {
            var charged = api.post("/api-admin/v1/points/charge", admin.getId(), Map.of("userId", user.getId(), "amount", 300)).assertSuccess(HttpStatus.OK);
            assertThat(charged.data().path("userId").asLong()).isEqualTo(user.getId());
            assertThat(charged.data().path("balance").asLong()).isEqualTo(1_300L);

            var deducted = api.post("/api-admin/v1/points/deduct", admin.getId(), Map.of("userId", user.getId(), "amount", 1_300)).assertSuccess(HttpStatus.OK);
            assertThat(deducted.data().path("balance").asLong()).isZero();
        }

        @DisplayName("[ER-01 USER_NOT_FOUND] 바디 userId 의 사용자가 없으면 404. 요청자 실패와 message 로 구분한다.")
        @Test
        void targetUserNotFound() {
            var result = api.post("/api-admin/v1/points/charge", admin.getId(), Map.of("userId", 999_999, "amount", 1)).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
            assertThat(result.message()).contains("999999");
        }

        @DisplayName("[ER-02 NOT_ADMIN] 관리자가 아니면 403.")
        @Test
        void notAdmin() {
            api.post("/api-admin/v1/points/charge", user.getId(), Map.of("userId", user.getId(), "amount", 1)).assertError(HttpStatus.FORBIDDEN, "NOT_ADMIN");
        }

        @DisplayName("[ER-09 INVALID_AMOUNT] amount 오류는 400, [ER-22 BAD_REQUEST] userId 누락·타입 오류는 400 BAD_REQUEST.")
        @Test
        void invalidAmountAndBadUserId() {
            api.post("/api-admin/v1/points/charge", admin.getId(), Map.of("userId", user.getId())).assertError(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
            api.post("/api-admin/v1/points/deduct", admin.getId(), Map.of("userId", user.getId(), "amount", "x")).assertError(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
            api.post("/api-admin/v1/points/charge", admin.getId(), Map.of("userId", "abc", "amount", 1)).assertError(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
            api.post("/api-admin/v1/points/charge", admin.getId(), Map.of("amount", 1)).assertError(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
            api.post("/api-admin/v1/points/deduct", admin.getId(), Map.of("amount", 1)).assertError(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        }

        @DisplayName("[ER-10 BALANCE_LIMIT_EXCEEDED] / [ER-11 INSUFFICIENT_POINT] 409.")
        @Test
        void conflicts() {
            UserModel rich = fixtures.userWithBalance(Long.MAX_VALUE);

            api.post("/api-admin/v1/points/charge", admin.getId(), Map.of("userId", rich.getId(), "amount", 1)).assertError(HttpStatus.CONFLICT, "BALANCE_LIMIT_EXCEEDED");
            api.post("/api-admin/v1/points/deduct", admin.getId(), Map.of("userId", user.getId(), "amount", 1_001)).assertError(HttpStatus.CONFLICT, "INSUFFICIENT_POINT");
        }
    }
}
