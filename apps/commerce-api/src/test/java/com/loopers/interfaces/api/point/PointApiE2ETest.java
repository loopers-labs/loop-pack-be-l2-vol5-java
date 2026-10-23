package com.loopers.interfaces.api.point;

import com.loopers.domain.common.Money;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointApiE2ETest {

    private static final String CHARGE_URL = "/api/v1/points/charge";
    private static final String POINT_URL = "/api/v1/points";
    private static final String USER_ID_HEADER = "X-USER-ID";

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private User saveUserWithBalance(long amount) {
        User user = User.create();
        if (amount > 0) {
            user.chargePoint(new Money(amount));
        }
        return userRepository.save(user);
    }

    private HttpHeaders headers(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userId != null) {
            headers.set(USER_ID_HEADER, userId);
        }
        return headers;
    }

    private ResponseEntity<ApiResponse<PointDto.PointResponse>> charge(String userId, String jsonBody) {
        return testRestTemplate.exchange(
            CHARGE_URL, HttpMethod.POST, new HttpEntity<>(jsonBody, headers(userId)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<PointDto.PointResponse>> getPoint(String userId) {
        return testRestTemplate.exchange(
            POINT_URL, HttpMethod.GET, new HttpEntity<>(headers(userId)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private void assertFail(ResponseEntity<ApiResponse<PointDto.PointResponse>> response, HttpStatus status, String errorCode) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta().errorCode()).isEqualTo(errorCode);
    }

    @DisplayName("POST /api/v1/points/charge")
    @Nested
    class Charge {
        @DisplayName("충전하면 충전 후 잔액을 반환한다.")
        @Test
        void returnsChargedBalance_whenAmountIsValid() {
            // arrange
            User user = saveUserWithBalance(5_000);

            // act
            ResponseEntity<ApiResponse<PointDto.PointResponse>> response =
                charge(String.valueOf(user.getId()), "{\"amount\": 10000}");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().balance()).isEqualTo(15_000);
        }

        @DisplayName("충전액이 0 이하이면 400 INVALID_INPUT 으로 응답한다.")
        @ParameterizedTest
        @ValueSource(strings = {"0", "-1"})
        void returnsBadRequest_whenAmountIsNotPositive(String amount) {
            User user = saveUserWithBalance(5_000);

            ResponseEntity<ApiResponse<PointDto.PointResponse>> response =
                charge(String.valueOf(user.getId()), "{\"amount\": " + amount + "}");

            assertFail(response, HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }

        @DisplayName("amount 가 소수이면 소수점을 버려 충전하지 않고, 400 INVALID_INPUT 이며 기존 잔액은 그대로다.")
        @ParameterizedTest
        @ValueSource(strings = {"1.5", "1000.0"})
        void returnsBadRequest_andKeepsBalance_whenAmountIsDecimal(String amount) {
            User user = saveUserWithBalance(5_000);

            ResponseEntity<ApiResponse<PointDto.PointResponse>> response =
                charge(String.valueOf(user.getId()), "{\"amount\": " + amount + "}");

            assertFail(response, HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            assertThat(getPoint(String.valueOf(user.getId())).getBody().data().balance()).isEqualTo(5_000);
        }

        @DisplayName("X-USER-ID 헤더가 없으면 400 INVALID_INPUT 으로 응답한다.")
        @Test
        void returnsBadRequest_whenUserIdHeaderIsMissing() {
            ResponseEntity<ApiResponse<PointDto.PointResponse>> response = charge(null, "{\"amount\": 1000}");

            assertFail(response, HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    @DisplayName("GET /api/v1/points")
    @Nested
    class GetPoint {
        @DisplayName("저장된 잔액을 반환한다.")
        @Test
        void returnsStoredBalance() {
            User user = saveUserWithBalance(7_000);

            ResponseEntity<ApiResponse<PointDto.PointResponse>> response = getPoint(String.valueOf(user.getId()));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().balance()).isEqualTo(7_000);
        }

        @DisplayName("X-USER-ID 헤더가 없으면 400 INVALID_INPUT 으로 응답한다.")
        @Test
        void returnsBadRequest_whenUserIdHeaderIsMissing() {
            assertFail(getPoint(null), HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }
}
