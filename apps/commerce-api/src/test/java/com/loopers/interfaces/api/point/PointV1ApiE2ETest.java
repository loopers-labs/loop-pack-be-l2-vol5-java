package com.loopers.interfaces.api.point;

import com.loopers.domain.user.UserModel;
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
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointV1ApiE2ETest {

    private static final String ENDPOINT = "/api/v1/points";
    private static final String CHARGE_ENDPOINT = ENDPOINT + "/charge";

    private final TestRestTemplate testRestTemplate;
    private final UserJpaRepository userJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public PointV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        UserJpaRepository userJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.userJpaRepository = userJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private UserModel createUser() {
        return userJpaRepository.save(new UserModel());
    }

    private <T> HttpEntity<T> requestWithUser(Long userId, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(body, headers);
    }

    @DisplayName("POST /api/v1/points/charge")
    @Nested
    class Charge {
        @DisplayName("존재하는 사용자가 양수 금액을 충전하면, 200과 충전 후 잔액을 반환한다.")
        @Test
        void returns200WithBalance_whenAmountIsPositive() {
            // arrange
            UserModel user = createUser();
            ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> responseType =
                new ParameterizedTypeReference<>() {};

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                CHARGE_ENDPOINT, HttpMethod.POST,
                requestWithUser(user.getId(), new PointV1Dto.ChargeRequest(1000L)),
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().balance()).isEqualTo(1000L);
        }

        @DisplayName("충전 금액이 0 이하이면, 400을 반환한다.")
        @Test
        void returns400_whenAmountIsNotPositive() {
            // arrange
            UserModel user = createUser();
            ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> responseType =
                new ParameterizedTypeReference<>() {};

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                CHARGE_ENDPOINT, HttpMethod.POST,
                requestWithUser(user.getId(), new PointV1Dto.ChargeRequest(0L)),
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("존재하지 않는 사용자면, 404를 반환한다.")
        @Test
        void returns404_whenUserDoesNotExist() {
            // arrange
            ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> responseType =
                new ParameterizedTypeReference<>() {};

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                CHARGE_ENDPOINT, HttpMethod.POST,
                requestWithUser(999L, new PointV1Dto.ChargeRequest(1000L)),
                responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("GET /api/v1/points")
    @Nested
    class GetBalance {
        @DisplayName("존재하는 사용자면, 200과 잔액을 반환한다.")
        @Test
        void returns200WithBalance_whenUserExists() {
            // arrange
            UserModel user = createUser();
            user.getPoint().charge(500L);
            userJpaRepository.saveAndFlush(user);
            ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> responseType =
                new ParameterizedTypeReference<>() {};

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT, HttpMethod.GET, requestWithUser(user.getId(), null), responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().balance()).isEqualTo(500L);
        }

        @DisplayName("존재하지 않는 사용자면, 404를 반환한다.")
        @Test
        void returns404_whenUserDoesNotExist() {
            // arrange
            ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> responseType =
                new ParameterizedTypeReference<>() {};

            // act
            ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
                ENDPOINT, HttpMethod.GET, requestWithUser(999L, null), responseType
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
