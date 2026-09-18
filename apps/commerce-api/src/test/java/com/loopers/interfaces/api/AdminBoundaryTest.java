package com.loopers.interfaces.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminBoundaryTest {

    private static final String ENDPOINT_ADMIN = "/api-admin/v1/products";
    private static final String ENDPOINT_CUSTOMER = "/api/v1/products";

    private final MockMvc mockMvc;

    @Autowired
    public AdminBoundaryTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @DisplayName("GET /api-admin/** 에 접근할 때, ")
    @Nested
    class GetAdminApi {
        @DisplayName("ROLE_ADMIN 사용자가 요청하면, 권한으로 거절되지 않는다.")
        @Test
        void doesNotReject_whenRequesterIsAdmin() throws Exception {
            // act
            int status = mockMvc.perform(get(ENDPOINT_ADMIN).with(user("admin").roles("ADMIN")))
                .andReturn().getResponse().getStatus();

            // assert
            assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        }

        @DisplayName("일반 사용자가 요청하면, 403 FORBIDDEN 응답을 받는다.")
        @Test
        void returnsForbidden_whenRequesterIsNotAdmin() throws Exception {
            mockMvc.perform(get(ENDPOINT_ADMIN).with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
        }

        @DisplayName("식별되지 않은 요청이면, 403 FORBIDDEN 응답을 받는다.")
        @Test
        void returnsForbidden_whenRequesterIsAnonymous() throws Exception {
            mockMvc.perform(get(ENDPOINT_ADMIN))
                .andExpect(status().isForbidden());
        }
    }

    @DisplayName("POST /api-admin/** 에 접근할 때, ")
    @Nested
    class PostAdminApi {
        @DisplayName("ROLE_ADMIN 사용자가 CSRF 토큰과 함께 요청하면, 권한으로 거절되지 않는다.")
        @Test
        void doesNotReject_whenRequesterIsAdminWithCsrf() throws Exception {
            // act
            int status = mockMvc.perform(post(ENDPOINT_ADMIN).with(user("admin").roles("ADMIN")).with(csrf()))
                .andReturn().getResponse().getStatus();

            // assert
            assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        }

        @DisplayName("일반 사용자가 CSRF 토큰과 함께 요청하면, 403 FORBIDDEN 응답을 받는다.")
        @Test
        void returnsForbidden_whenRequesterIsNotAdmin() throws Exception {
            mockMvc.perform(post(ENDPOINT_ADMIN).with(user("user").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        }

        @DisplayName("식별되지 않은 요청이면, 403 FORBIDDEN 응답을 받는다.")
        @Test
        void returnsForbidden_whenRequesterIsAnonymous() throws Exception {
            mockMvc.perform(post(ENDPOINT_ADMIN).with(csrf()))
                .andExpect(status().isForbidden());
        }

        @DisplayName("ROLE_ADMIN 사용자라도 CSRF 토큰이 없으면, 403 FORBIDDEN 응답을 받는다.")
        @Test
        void returnsForbidden_whenCsrfTokenIsMissing() throws Exception {
            mockMvc.perform(post(ENDPOINT_ADMIN).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        }
    }

    @DisplayName("고객 API 에 접근할 때, ")
    @Nested
    class CustomerApi {
        @DisplayName("식별되지 않은 요청이어도, 인증으로 거절되지 않는다.")
        @Test
        void doesNotReject_whenRequesterIsAnonymous() throws Exception {
            // act
            int status = mockMvc.perform(get(ENDPOINT_CUSTOMER))
                .andReturn().getResponse().getStatus();

            // assert
            assertThat(status).isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
        }
    }
}
