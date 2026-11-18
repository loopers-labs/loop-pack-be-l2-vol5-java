package com.loopers.interfaces.api.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.ApiTestClient;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 테스트용 사용자 생성 POST /api/v1/users (DR-59). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserV1ApiE2ETest {

    private static final String PATH = "/api/v1/users";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ApiTestClient api;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(restTemplate, objectMapper);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("헤더 없이 일반 사용자를 만들고, 잔액 0 인 포인트 계정이 함께 생겨 바로 잔액을 조회할 수 있다.")
    @Test
    void createsUserWithPointAccount() {
        var created = api.post(PATH, null, Map.of("admin", false)).assertSuccess(HttpStatus.OK);

        long userId = created.data().path("userId").asLong();
        assertThat(userId).isPositive();
        assertThat(created.data().path("admin").asBoolean()).isFalse();
        assertThat(fixtures.balanceOf(userId)).isZero();
        api.get("/api/v1/points", userId).assertSuccess(HttpStatus.OK);
    }

    @DisplayName("본문이 없거나 admin 이 없으면 일반 사용자다.")
    @Test
    void defaultsToNormalUser() {
        assertThat(api.post(PATH, null, null).assertSuccess(HttpStatus.OK).data().path("admin").asBoolean()).isFalse();
        assertThat(api.post(PATH, null, Map.of()).assertSuccess(HttpStatus.OK).data().path("admin").asBoolean()).isFalse();
    }

    @DisplayName("admin=true 면 관리자이고 관리자 API 를 쓸 수 있다. 일반 사용자는 403 이다.")
    @Test
    void createsAdmin() {
        var admin = api.post(PATH, null, Map.of("admin", true)).assertSuccess(HttpStatus.OK);
        var user = api.post(PATH, null, Map.of("admin", false)).assertSuccess(HttpStatus.OK);
        long adminId = admin.data().path("userId").asLong();
        long userId = user.data().path("userId").asLong();
        assertThat(admin.data().path("admin").asBoolean()).isTrue();
        assertThat(fixtures.balanceOf(adminId)).isZero();

        api.post("/api-admin/v1/points/charge", adminId, Map.of("userId", userId, "amount", 100)).assertSuccess(HttpStatus.OK);
        api.post("/api-admin/v1/points/charge", userId, Map.of("userId", userId, "amount", 100)).assertError(HttpStatus.FORBIDDEN, "NOT_ADMIN");
        assertThat(fixtures.balanceOf(userId)).isEqualTo(100L);
    }
}
