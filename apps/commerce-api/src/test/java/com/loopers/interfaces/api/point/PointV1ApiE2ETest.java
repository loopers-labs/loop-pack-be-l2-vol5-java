package com.loopers.interfaces.api.point;

import com.loopers.domain.point.PointService;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.UserFixture;
import com.loopers.infrastructure.point.PointHistoryJpaRepository;
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

@DisplayName("포인트 API 는 요청자의 잔액을 충전하고 조회한다.")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointV1ApiE2ETest {

    private static final String ENDPOINT_CHARGE = "/api/v1/points/charge";
    private static final String ENDPOINT_BALANCE = "/api/v1/points";
    private static final String USER_ID_HEADER = "X-USER-ID";

    private static final ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate testRestTemplate;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private PointService pointService;
    @Autowired
    private PointJpaRepository pointJpaRepository;
    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpEntity<Object> request(Object body, String userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, userId);
        }
        return new HttpEntity<>(body, headers);
    }

    @DisplayName("POST /api/v1/points/charge")
    @Nested
    class Charge {
        @DisplayName("충전액을 잔액에 더해 저장하고 충전 후 잔액을 반환한다.")
        @Test
        void returnsChargedBalance() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT_CHARGE, HttpMethod.POST,
                request(Map.of("amount", 10_000L), String.valueOf(user.getId())), RESPONSE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().balance()).isEqualTo(10_000L),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance())
                    .isEqualTo(10_000L),
                () -> assertThat(pointHistoryJpaRepository.findAll()).hasSize(1)
            );
        }

        @DisplayName("충전액 0 은 INVALID_POINT_AMOUNT 로 거절하고 저장된 잔액을 유지한다.")
        @Test
        void rejectsZeroAmount() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 1_000L);

            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT_CHARGE, HttpMethod.POST,
                request(Map.of("amount", 0L), String.valueOf(user.getId())), RESPONSE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_POINT_AMOUNT"),
                () -> assertThat(response.getBody().data()).isNull(),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance())
                    .isEqualTo(1_000L),
                () -> assertThat(pointHistoryJpaRepository.findAll()).hasSize(1)
            );
        }

        @DisplayName("amount 가 누락되면 INVALID_POINT_AMOUNT 로 거절한다.")
        @Test
        void rejectsMissingAmount() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT_CHARGE, HttpMethod.POST,
                request(Map.of(), String.valueOf(user.getId())), RESPONSE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_POINT_AMOUNT"),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isZero()
            );
        }

        @DisplayName("amount 의 타입이 잘못되면 400 으로 거절하고 잔액을 유지한다.")
        @Test
        void rejectsInvalidAmountType() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT_CHARGE, HttpMethod.POST,
                request(Map.of("amount", "만원"), String.valueOf(user.getId())), RESPONSE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isZero(),
                () -> assertThat(pointHistoryJpaRepository.findAll()).isEmpty()
            );
        }

        @DisplayName("Point 가 없는 사용자의 충전은 500 POINT_NOT_INITIALIZED 로 응답한다.")
        @Test
        void rejectsWhenPointIsNotInitialized() {
            UserModel user = userFixture.createUserWithoutPoint();

            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT_CHARGE, HttpMethod.POST,
                request(Map.of("amount", 10_000L), String.valueOf(user.getId())), RESPONSE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("POINT_NOT_INITIALIZED"),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId())).isEmpty()
            );
        }
    }

    @DisplayName("GET /api/v1/points")
    @Nested
    class GetBalance {
        @DisplayName("요청자로 식별한 사용자의 저장된 잔액을 반환한다.")
        @Test
        void returnsStoredBalance() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 4_200L);

            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT_BALANCE, HttpMethod.GET, request(null, String.valueOf(user.getId())), RESPONSE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().balance()).isEqualTo(4_200L)
            );
        }

        @DisplayName("다른 사용자의 충전은 요청자의 잔액에 반영되지 않는다.")
        @Test
        void returnsOnlyOwnBalance() {
            UserModel me = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            pointService.charge(other.getId(), 9_000L);

            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT_BALANCE, HttpMethod.GET, request(null, String.valueOf(me.getId())), RESPONSE_TYPE);

            assertThat(response.getBody().data().balance()).isZero();
        }
    }

    @DisplayName("공통 요청자 식별")
    @Nested
    class CustomerIdentification {
        @DisplayName("X-USER-ID 헤더가 없으면 400 INVALID_REQUEST 로 거절한다.")
        @Test
        void rejectsMissingHeader() {
            userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT_BALANCE, HttpMethod.GET, request(null, null), RESPONSE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_REQUEST")
            );
        }

        @DisplayName("X-USER-ID 가 숫자가 아니면 400 INVALID_REQUEST 로 거절한다.")
        @Test
        void rejectsNonNumericHeader() {
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT_BALANCE, HttpMethod.GET, request(null, "abc"), RESPONSE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_REQUEST")
            );
        }

        @DisplayName("저장되지 않은 사용자 ID 로 요청하면 404 USER_NOT_FOUND 로 거절하고 충전을 진행하지 않는다.")
        @Test
        void rejectsUnknownUser() {
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT_CHARGE, HttpMethod.POST, request(Map.of("amount", 10_000L), "999999"), RESPONSE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("USER_NOT_FOUND"),
                () -> assertThat(pointJpaRepository.findAll()).isEmpty(),
                () -> assertThat(pointHistoryJpaRepository.findAll()).isEmpty()
            );
        }
    }
}
