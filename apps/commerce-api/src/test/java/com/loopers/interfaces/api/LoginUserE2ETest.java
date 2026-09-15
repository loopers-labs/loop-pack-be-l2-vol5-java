package com.loopers.interfaces.api;

import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LoginUserE2ETest.LoginUserProbeController.class)
class LoginUserE2ETest {

    private static final String ENDPOINT = "/api/v1/test/login-user";
    private static final ParameterizedTypeReference<ApiResponse<Long>> RESPONSE_TYPE = new ParameterizedTypeReference<>() {};

    /**
     * 테스트 전용 컨트롤러. LoginUser 파라미터를 받아 식별된 ID를 그대로 돌려준다.
     * 테스트 클래스의 중첩 클래스는 컴포넌트 스캔에서 제외되므로 이 테스트의 컨텍스트에만 @Import로 등록된다.
     */
    @RestController
    static class LoginUserProbeController {
        @GetMapping(ENDPOINT)
        ApiResponse<Long> identify(LoginUser loginUser) {
            return ApiResponse.success(loginUser.id());
        }
    }

    private final TestRestTemplate testRestTemplate;
    private final UserJpaRepository userJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public LoginUserE2ETest(
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

    private ResponseEntity<ApiResponse<Long>> requestWithUserId(String userIdHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (userIdHeader != null) {
            headers.set("X-USER-ID", userIdHeader);
        }
        return testRestTemplate.exchange(ENDPOINT, HttpMethod.GET, new HttpEntity<>(headers), RESPONSE_TYPE);
    }

    private void assertUnauthorized(ResponseEntity<ApiResponse<Long>> response) {
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED),
            () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Unauthorized"),
            () -> assertThat(response.getBody().data()).isNull()
        );
    }

    @DisplayName("USR-01 · X-USER-ID가 필요한 API를 호출할 때, ")
    @Nested
    class Identify {

        @DisplayName("존재하는 사용자 ID를 보내면, 200 SUCCESS와 그 ID로 처리된다.")
        @Test
        void identifiesUser_whenUserExists() {
            // arrange
            UserModel user = userJpaRepository.save(new UserModel("사용자"));

            // act
            ResponseEntity<ApiResponse<Long>> response = requestWithUserId(String.valueOf(user.getId()));

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
                () -> assertThat(response.getBody().data()).isEqualTo(user.getId())
            );
        }

        @DisplayName("헤더가 없으면, 401 Unauthorized 응답을 받는다.")
        @Test
        void returnsUnauthorized_whenHeaderIsMissing() {
            // act
            ResponseEntity<ApiResponse<Long>> response = requestWithUserId(null);

            // assert
            assertUnauthorized(response);
        }

        @DisplayName("헤더가 숫자가 아니면 (abc), 401 Unauthorized 응답을 받는다.")
        @Test
        void returnsUnauthorized_whenHeaderIsNotNumeric() {
            // act
            ResponseEntity<ApiResponse<Long>> response = requestWithUserId("abc");

            // assert
            assertUnauthorized(response);
        }

        @DisplayName("헤더가 빈칸이면, 401 Unauthorized 응답을 받는다.")
        @Test
        void returnsUnauthorized_whenHeaderIsBlank() {
            // act
            ResponseEntity<ApiResponse<Long>> response = requestWithUserId(" ");

            // assert
            assertUnauthorized(response);
        }

        @DisplayName("존재하지 않는 사용자 ID이면 (999), 401 Unauthorized 응답을 받는다.")
        @Test
        void returnsUnauthorized_whenUserDoesNotExist() {
            // act
            ResponseEntity<ApiResponse<Long>> response = requestWithUserId("999");

            // assert
            assertUnauthorized(response);
        }
    }
}
