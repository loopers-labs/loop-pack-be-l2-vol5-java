package com.loopers.interfaces.api.point;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.infrastructure.point.PointJpaRepository;
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
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointV1ApiE2ETest {

    private static final String ENDPOINT_POINTS = "/api/v1/points";
    private static final String ENDPOINT_CHARGE = "/api/v1/points/charge";

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("GET /api/v1/points")
    @Nested
    class GetBalance {
        @DisplayName("요청자 식별값을 주면, 저장된 포인트 잔액을 반환한다.")
        @Test
        void returnsSavedBalance_whenUserIdIsProvided() {
            // arrange
            Point point = pointJpaRepository.save(Point.create(1L, new PointBalance(300L)));
            HttpEntity<Void> request = new HttpEntity<>(headers(point.getUserId()));

            // act
            ParameterizedTypeReference<ApiResponse<PointV1Dto.BalanceResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PointV1Dto.BalanceResponse>> response = testRestTemplate.exchange(
                ENDPOINT_POINTS,
                HttpMethod.GET,
                request,
                responseType
            );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().balance()).isEqualTo(300L)
            );
        }
    }

    @DisplayName("POST /api/v1/points/charge")
    @Nested
    class Charge {
        @DisplayName("양수 amount와 요청자 식별값을 주면, 충전 후 잔액을 반환한다.")
        @Test
        void returnsChargedBalance_whenValidRequestIsProvided() {
            // arrange
            Point point = pointJpaRepository.save(Point.create(1L));
            HttpEntity<PointV1Dto.ChargeRequest> request = new HttpEntity<>(
                new PointV1Dto.ChargeRequest(200L),
                headers(point.getUserId())
            );

            // act
            ParameterizedTypeReference<ApiResponse<PointV1Dto.ChargeResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PointV1Dto.ChargeResponse>> response = testRestTemplate.exchange(
                ENDPOINT_CHARGE,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            Point savedPoint = pointJpaRepository.findByUserId(point.getUserId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().balance()).isEqualTo(200L),
                () -> assertThat(savedPoint.getBalance().amount()).isEqualTo(200L)
            );
        }

        @DisplayName("amount가 누락되면, 400 응답을 반환하고 기존 잔액을 유지한다.")
        @Test
        void keepsBalance_whenAmountIsMissing() {
            // arrange
            Point point = pointJpaRepository.save(Point.create(1L));
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of(), headers(point.getUserId()));

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ENDPOINT_CHARGE,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            Point savedPoint = pointJpaRepository.findByUserId(point.getUserId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(savedPoint.getBalance().amount()).isZero()
            );
        }

        @DisplayName("amount가 0이면, 400 응답을 반환하고 기존 잔액을 유지한다.")
        @Test
        void keepsBalance_whenAmountIsZero() {
            // arrange
            Point point = pointJpaRepository.save(Point.create(1L));
            HttpEntity<PointV1Dto.ChargeRequest> request = new HttpEntity<>(
                new PointV1Dto.ChargeRequest(0L),
                headers(point.getUserId())
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ENDPOINT_CHARGE,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            Point savedPoint = pointJpaRepository.findByUserId(point.getUserId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(savedPoint.getBalance().amount()).isZero()
            );
        }

        @DisplayName("amount의 타입이 잘못되면, 400 응답을 반환하고 기존 잔액을 유지한다.")
        @Test
        void keepsBalance_whenAmountHasInvalidType() {
            // arrange
            Point point = pointJpaRepository.save(Point.create(1L));
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(
                Map.of("amount", "invalid"),
                headers(point.getUserId())
            );

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ENDPOINT_CHARGE,
                HttpMethod.POST,
                request,
                responseType
            );

            // assert
            Point savedPoint = pointJpaRepository.findByUserId(point.getUserId()).orElseThrow();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(savedPoint.getBalance().amount()).isZero()
            );
        }
    }

    private HttpHeaders headers(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", userId.toString());
        return headers;
    }
}
