package com.loopers.interfaces.api;

import com.loopers.interfaces.api.point.PointV1Dto;
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

    private final TestRestTemplate testRestTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public PointV1ApiE2ETest(TestRestTemplate testRestTemplate, DatabaseCleanUp databaseCleanUp) {
        this.testRestTemplate = testRestTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpEntity<Object> withUser(Long userId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @DisplayName("GET /api/v1/points")
    @Nested
    class Get {
        @DisplayName("한 번도 충전한 적 없는 사용자가 조회하면, 잔액 0을 응답한다.")
        @Test
        void returnsZeroBalance_whenNeverCharged() {
            // act
            ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response =
                testRestTemplate.exchange("/api/v1/points", HttpMethod.GET, withUser(1L, null), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().balance()).isEqualTo(0L);
        }
    }

    @DisplayName("POST /api/v1/points/charge")
    @Nested
    class Charge {
        @DisplayName("포인트를 충전하면, 잔액에 반영된다.")
        @Test
        void reflectsBalance_whenCharged() {
            // act
            ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                "/api/v1/points/charge", HttpMethod.POST, withUser(1L, new PointV1Dto.ChargeRequest(1_000L)), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().balance()).isEqualTo(1_000L);
        }

        @DisplayName("여러 번 충전하면, 잔액이 누적된다.")
        @Test
        void accumulatesBalance_whenChargedMultipleTimes() {
            // arrange
            testRestTemplate.exchange("/api/v1/points/charge", HttpMethod.POST,
                withUser(1L, new PointV1Dto.ChargeRequest(1_000L)), Void.class);

            // act
            ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                "/api/v1/points/charge", HttpMethod.POST, withUser(1L, new PointV1Dto.ChargeRequest(2_000L)), responseType);

            // assert
            assertThat(response.getBody().data().balance()).isEqualTo(3_000L);
        }

        @DisplayName("충전 금액이 0 이하이면, 400을 응답한다.")
        @Test
        void returns400_whenAmountIsNotPositive() {
            // act
            ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                "/api/v1/points/charge", HttpMethod.POST, withUser(1L, new PointV1Dto.ChargeRequest(0L)), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("충전 후 잔액이 10억을 넘으면, 400을 응답한다.")
        @Test
        void returns400_whenBalanceExceedsMaximum() {
            // arrange
            testRestTemplate.exchange("/api/v1/points/charge", HttpMethod.POST,
                withUser(1L, new PointV1Dto.ChargeRequest(999_999_999L)), Void.class);

            // act
            ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                "/api/v1/points/charge", HttpMethod.POST, withUser(1L, new PointV1Dto.ChargeRequest(2L)), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
