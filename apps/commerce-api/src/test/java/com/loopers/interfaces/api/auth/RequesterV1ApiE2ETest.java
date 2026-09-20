package com.loopers.interfaces.api.auth;

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

/** 공통 요청자 식별 ER-01·ER-02 의 HTTP 계약 (설계 4-4). 고객 API 는 상품 목록, 관리자 API 는 브랜드로 대표한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequesterV1ApiE2ETest {

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
        user = fixtures.user();
        admin = fixtures.admin();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("공통: 요청자 식별 (ER-01, ER-02)")
    class Requester {
        @DisplayName("[ER-01 USER_NOT_FOUND] 고객 API 에서 헤더 누락은 404.")
        @Test
        void customer_missingHeader() {
            api.get("/api/v1/products", null).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        @DisplayName("[ER-01 USER_NOT_FOUND] 고객 API 에서 헤더 형식 오류는 404 (DR-01).")
        @Test
        void customer_malformedHeader() {
            api.get("/api/v1/products", "abc").assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        @DisplayName("[ER-01 USER_NOT_FOUND] 고객 API 에서 없는 사용자는 404.")
        @Test
        void customer_unknownUser() {
            api.get("/api/v1/products", 999_999L).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        @DisplayName("[ER-01 USER_NOT_FOUND] 관리자 API 에서 헤더 누락·없는 사용자는 404 봉투로 나간다.")
        @Test
        void admin_userNotFound() {
            api.get("/api-admin/v1/brands", null).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
            api.get("/api-admin/v1/brands", 999_999L).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        @DisplayName("[ER-02 NOT_ADMIN] 관리자 API 에서 관리자가 아닌 사용자는 403 봉투.")
        @Test
        void admin_notAdmin() {
            api.get("/api-admin/v1/brands", user.getId()).assertError(HttpStatus.FORBIDDEN, "NOT_ADMIN");
            api.post("/api-admin/v1/brands", user.getId(), Map.of("name", "x")).assertError(HttpStatus.FORBIDDEN, "NOT_ADMIN");
        }

        @DisplayName("관리자 API 는 CSRF 토큰 없이 POST/PUT/DELETE 가 통과한다.")
        @Test
        void admin_writesWithoutCsrf() {
            api.post("/api-admin/v1/brands", admin.getId(), Map.of("name", "브랜드")).assertSuccess(HttpStatus.CREATED);
        }
    }
}
