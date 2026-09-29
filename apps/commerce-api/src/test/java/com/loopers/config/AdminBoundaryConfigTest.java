package com.loopers.config;

import com.loopers.interfaces.api.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminBoundaryConfigTest.CustomerPostProbeController.class)
class AdminBoundaryConfigTest {

    private static final String ADMIN_ENDPOINT = "/api-admin/v1/brands";
    private static final String CUSTOMER_POST_ENDPOINT = "/api/v1/test/security-probe";

    /**
     * 테스트 전용 고객 POST 엔드포인트. Security 필터를 통과하면 200을 돌려준다.
     * 테스트 클래스의 중첩 클래스는 컴포넌트 스캔에서 제외되므로 이 테스트의 컨텍스트에만 @Import로 등록된다.
     */
    @RestController
    static class CustomerPostProbeController {
        @PostMapping(CUSTOMER_POST_ENDPOINT)
        ApiResponse<Object> probe() {
            return ApiResponse.success();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @DisplayName("관리자 API(/api-admin/**)를 호출할 때, ")
    @Nested
    class AdminApi {

        @DisplayName("ROLE_ADMIN 사용자는 관리자 브랜드 목록을 200으로 받는다.")
        @Test
        void allows_whenUserHasAdminRole() throws Exception {
            // act
            int status = mockMvc.perform(get(ADMIN_ENDPOINT).with(user("admin").roles("ADMIN")))
                .andReturn().getResponse().getStatus();

            // assert
            assertThat(status).isEqualTo(200);
        }

        @DisplayName("ROLE_USER 사용자는 403으로 거절된다.")
        @Test
        void forbids_whenUserHasOnlyUserRole() throws Exception {
            // act
            int status = mockMvc.perform(get(ADMIN_ENDPOINT).with(user("customer").roles("USER")))
                .andReturn().getResponse().getStatus();

            // assert
            assertThat(status).isEqualTo(403);
        }

        @DisplayName("인증되지 않은 요청은 403으로 거절된다.")
        @Test
        void forbids_whenAnonymous() throws Exception {
            // act
            int status = mockMvc.perform(get(ADMIN_ENDPOINT))
                .andReturn().getResponse().getStatus();

            // assert
            assertThat(status).isEqualTo(403);
        }
    }

    @DisplayName("고객 API(/api/v1/**)를 호출할 때, ")
    @Nested
    class CustomerApi {

        @DisplayName("CSRF 토큰 없는 POST도 Security에서 거절되지 않는다.")
        @Test
        void doesNotForbidPostWithoutCsrf() throws Exception {
            // act
            int status = mockMvc.perform(post(CUSTOMER_POST_ENDPOINT))
                .andReturn().getResponse().getStatus();

            // assert
            // Spring Boot 기본 체인이 적용됐다면 CSRF 토큰이 없어 403이 된다.
            // 테스트 전용 엔드포인트까지 도달했음을 200으로 확인해, 403이 아님을 더 강하게 보인다.
            assertThat(status).isNotEqualTo(403).isEqualTo(200);
        }
    }
}
