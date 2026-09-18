package com.loopers.interfaces.api.point;

import com.loopers.domain.user.User;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final String ENDPOINT_CHARGE = "/api/v1/points/charge";
    private static final String ENDPOINT_GET = "/api/v1/points";

    private final TestRestTemplate testRestTemplate;
    private final UserJpaRepository userJpaRepository;
    private final PointJpaRepository pointJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public PointV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        UserJpaRepository userJpaRepository,
        PointJpaRepository pointJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.userJpaRepository = userJpaRepository;
        this.pointJpaRepository = pointJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api/v1/points/charge")
    @Nested
    class Charge {
        @DisplayName("양수 금액을 충전하면, 200과 충전 후 잔액을 받고 DB에 저장된다.")
        @Test
        void returnsChargedBalanceAndPersists_whenAmountIsPositive() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = charge(userId, "1000");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().balance()).isEqualTo(1000L);
            assertThat(pointJpaRepository.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(1000L);
        }

        @DisplayName("두 번 충전하면, 잔액이 누적된다.")
        @Test
        void accumulatesBalance_whenChargedTwice() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            charge(userId, "1000");

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = charge(userId, "500");

            // assert
            assertThat(response.getBody().data().balance()).isEqualTo(1500L);
        }

        @DisplayName("0 이하 금액을 충전하면, 400 응답을 받고 기존 잔액이 유지된다.")
        @Test
        void keepsBalance_whenAmountIsNotPositive() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            charge(userId, "1000");

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = charge(userId, "0");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(pointJpaRepository.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(1000L);
        }

        @DisplayName("금액이 누락되면, 400 응답을 받는다.")
        @Test
        void returnsBadRequest_whenAmountIsMissing() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = charge(userId, null);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("금액 타입이 잘못되면, 400 응답을 받는다.")
        @Test
        void returnsBadRequest_whenAmountTypeIsInvalid() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = charge(userId, "\"천원\"");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("표현 범위를 넘는 충전이면, 400 응답을 받고 기존 잔액이 유지된다.")
        @Test
        void keepsBalance_whenResultOverflows() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            charge(userId, String.valueOf(Long.MAX_VALUE));

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = charge(userId, "1");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(pointJpaRepository.findByUserId(userId).orElseThrow().getBalance())
                .isEqualTo(Long.MAX_VALUE);
        }

        @DisplayName("식별 헤더가 없으면, 401 응답을 받고 저장되지 않는다.")
        @Test
        void returnsUnauthorized_whenHeaderIsMissing() {
            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = charge(null, "1000");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(pointJpaRepository.findAll()).isEmpty();
        }
    }

    @DisplayName("GET /api/v1/points")
    @Nested
    class GetBalance {
        @DisplayName("충전한 사용자가 조회하면, 200과 저장된 잔액을 받는다.")
        @Test
        void returnsStoredBalance_whenCharged() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();
            charge(userId, "1000");

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = getBalance(userId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().balance()).isEqualTo(1000L);
        }

        @DisplayName("충전한 적 없는 사용자가 조회하면, 200과 0원을 받는다.")
        @Test
        void returnsZero_whenNeverCharged() {
            // arrange
            Long userId = userJpaRepository.save(new User()).getId();

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = getBalance(userId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().balance()).isZero();
        }

        @DisplayName("식별 헤더가 없으면, 401 응답을 받는다.")
        @Test
        void returnsUnauthorized_whenHeaderIsMissing() {
            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = getBalance(null);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    private ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> charge(Long userId, String amountJson) {
        String body = amountJson == null ? "{}" : "{\"amount\":" + amountJson + "}";
        HttpHeaders headers = headers(userId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        return testRestTemplate.exchange(
            ENDPOINT_CHARGE, HttpMethod.POST, new HttpEntity<>(body, headers), responseType());
    }

    private ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> getBalance(Long userId) {
        return testRestTemplate.exchange(
            ENDPOINT_GET, HttpMethod.GET, new HttpEntity<>(null, headers(userId)), responseType());
    }

    private ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> responseType() {
        return new ParameterizedTypeReference<>() {};
    }

    private HttpHeaders headers(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, String.valueOf(userId));
        }
        return headers;
    }
}
