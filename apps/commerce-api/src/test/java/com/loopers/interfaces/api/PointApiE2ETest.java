package com.loopers.interfaces.api;

import com.loopers.domain.user.User;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.point.PointDto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final String POINTS_ENDPOINT = "/api/v1/points";
    private static final String CHARGE_ENDPOINT = "/api/v1/points/charge";

    private final TestRestTemplate testRestTemplate;
    private final UserJpaRepository userJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    private User user;

    @Autowired
    PointApiE2ETest(TestRestTemplate testRestTemplate, UserJpaRepository userJpaRepository, DatabaseCleanUp databaseCleanUp) {
        this.testRestTemplate = testRestTemplate;
        this.userJpaRepository = userJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new User("user1"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpHeaders headersOf(Long requesterId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(USER_ID_HEADER, String.valueOf(requesterId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<ApiResponse<PointDto.BalanceResponse>> charge(Long requesterId, String jsonBody) {
        ParameterizedTypeReference<ApiResponse<PointDto.BalanceResponse>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(CHARGE_ENDPOINT, HttpMethod.POST, new HttpEntity<>(jsonBody, headersOf(requesterId)), responseType);
    }

    private ResponseEntity<ApiResponse<PointDto.BalanceResponse>> getBalance(Long requesterId) {
        ParameterizedTypeReference<ApiResponse<PointDto.BalanceResponse>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(POINTS_ENDPOINT, HttpMethod.GET, new HttpEntity<>(headersOf(requesterId)), responseType);
    }

    private long balanceInDb() {
        return userJpaRepository.findById(user.getId()).orElseThrow().getBalance();
    }

    @DisplayName("POST /api/v1/points/charge")
    @Nested
    class Charge {

        @DisplayName("잔액 0에서 1,000을 충전하면, 충전 후 잔액 1,000을 반환하고 DB에 저장된다. (PNT-001)")
        @Test
        void returnsChargedBalance_whenAmountIsPositive() {
            // act
            ResponseEntity<ApiResponse<PointDto.BalanceResponse>> response = charge(user.getId(), "{\"amount\": 1000}");

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().balance()).isEqualTo(1_000L),
                () -> assertThat(balanceInDb()).isEqualTo(1_000L)
            );
        }

        @DisplayName("충전액이 0·음수·누락·문자·표현 범위 초과면, 400 BAD_REQUEST 응답을 받고 잔액이 유지된다. (PNT-001, PNT-003)")
        @ParameterizedTest
        @ValueSource(strings = {
            "{\"amount\": 0}",
            "{\"amount\": -1000}",
            "{}",
            "{\"amount\": \"abc\"}",
            "{\"amount\": 99999999999999999999}"
        })
        void returnsBadRequest_andKeepsBalance_whenAmountIsInvalid(String jsonBody) {
            // arrange
            charge(user.getId(), "{\"amount\": 5000}");

            // act
            ResponseEntity<ApiResponse<PointDto.BalanceResponse>> response = charge(user.getId(), jsonBody);

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                () -> assertThat(balanceInDb()).isEqualTo(5_000L)
            );
        }

        @DisplayName("충전 후 잔액이 표현 범위를 넘으면, 400 BAD_REQUEST 응답을 받고 잔액이 유지된다. (PNT-002)")
        @Test
        void returnsBadRequest_whenBalanceOverflows() {
            // arrange
            charge(user.getId(), "{\"amount\": " + Long.MAX_VALUE + "}");

            // act
            ResponseEntity<ApiResponse<PointDto.BalanceResponse>> response = charge(user.getId(), "{\"amount\": 1}");

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(balanceInDb()).isEqualTo(Long.MAX_VALUE)
            );
        }

        @DisplayName("존재하지 않는 사용자가 충전하면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void returnsNotFound_whenUserDoesNotExist() {
            // act
            ResponseEntity<ApiResponse<PointDto.BalanceResponse>> response = charge(999999L, "{\"amount\": 1000}");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("GET /api/v1/points")
    @Nested
    class GetBalance {

        @DisplayName("충전 이력이 없는 사용자가 조회하면, 오류 없이 잔액 0을 반환한다. (PNT-002, P-5)")
        @Test
        void returnsZero_whenNeverCharged() {
            // act
            ResponseEntity<ApiResponse<PointDto.BalanceResponse>> response = getBalance(user.getId());

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().balance()).isZero()
            );
        }

        @DisplayName("충전한 사용자가 조회하면, 충전된 잔액을 반환한다.")
        @Test
        void returnsChargedBalance_whenCharged() {
            // arrange
            charge(user.getId(), "{\"amount\": 3000}");

            // act
            ResponseEntity<ApiResponse<PointDto.BalanceResponse>> response = getBalance(user.getId());

            // assert
            assertThat(response.getBody().data().balance()).isEqualTo(3_000L);
        }
    }
}
