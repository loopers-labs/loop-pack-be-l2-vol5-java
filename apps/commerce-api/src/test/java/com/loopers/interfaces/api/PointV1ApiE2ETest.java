package com.loopers.interfaces.api;

import com.loopers.domain.point.PointModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.point.PointV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

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

    private UserModel savedUser() {
        return userJpaRepository.save(new UserModel("실습 사용자"));
    }

    private ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> charge(Long userId, Long amount) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, String.valueOf(userId));
        }
        return testRestTemplate.exchange(
            "/api/v1/points/charge", HttpMethod.POST,
            new HttpEntity<>(new PointV1Dto.ChargeRequest(amount), headers), RESPONSE_TYPE
        );
    }

    private ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> getPoint(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, String.valueOf(userId));
        }
        return testRestTemplate.exchange(
            "/api/v1/points", HttpMethod.GET, new HttpEntity<>(null, headers), RESPONSE_TYPE
        );
    }

    @DisplayName("POST /api/v1/points/charge")
    @Test
    void chargesPoint_andReturnsBalance() {
        // arrange
        UserModel user = savedUser();

        // act
        ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = charge(user.getId(), 1_000L);

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(response.getBody().data().balance()).isEqualTo(1_000L),
            () -> assertThat(pointJpaRepository.findByUserId(user.getId()))
                .map(PointModel::getBalance).contains(1_000L)
        );
    }

    @DisplayName("존재하지 않는 사용자의 충전 요청은, 404 NOT_FOUND로 거절한다.")
    @Test
    void rejectsCharge_whenUserDoesNotExist() {
        // act
        ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = charge(999_999L, 1_000L);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @DisplayName("X-USER-ID 헤더가 없으면, 400 BAD_REQUEST로 거절한다.")
    @Test
    void rejectsCharge_whenUserIdHeaderIsMissing() {
        // act
        ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = charge(null, 1_000L);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @DisplayName("0 이하의 충전액은 400으로 거절하고, 저장된 잔액을 유지한다.")
    @Test
    void rejectsCharge_andKeepsBalance_whenAmountIsNotPositive() {
        // arrange
        UserModel user = savedUser();
        charge(user.getId(), 5_000L);

        // act
        ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = charge(user.getId(), 0L);

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(pointJpaRepository.findByUserId(user.getId()))
                .map(PointModel::getBalance).contains(5_000L)
        );
    }

    @DisplayName("GET /api/v1/points — 저장된 잔액을 조회한다.")
    @Test
    void returnsStoredBalance() {
        // arrange
        UserModel user = savedUser();
        charge(user.getId(), 3_000L);

        // act
        ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = getPoint(user.getId());

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(response.getBody().data().balance()).isEqualTo(3_000L)
        );
    }

    @DisplayName("충전 이력이 없는 사용자의 잔액은 0원이다.")
    @Test
    void returnsZeroBalance_whenNoChargeHistory() {
        // arrange
        UserModel user = savedUser();

        // act
        ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = getPoint(user.getId());

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(response.getBody().data().balance()).isEqualTo(0L)
        );
    }
}
