package com.loopers.interfaces.api.admin;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminBoundaryTest {

    private final MockMvc mvc;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    AdminBoundaryTest(MockMvc mvc, DatabaseCleanUp databaseCleanUp) {
        this.mvc = mvc;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("관리자는 관리자 경로를 부를 수 있다.")
    @Test
    void allowsAdmin() throws Exception {
        mvc.perform(get("/api-admin/v1/brands/1").with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound());
    }

    @DisplayName("일반 사용자는 관리자 경로를 부를 수 없다.")
    @Test
    void rejectsCustomer() throws Exception {
        mvc.perform(get("/api-admin/v1/brands/1").with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @DisplayName("식별되지 않은 요청도 거절된다. 401 이 아니라 403 이다 — 로그인 창을 띄우지 않는다.")
    @Test
    void rejectsAnonymous() throws Exception {
        mvc.perform(get("/api-admin/v1/brands/1"))
            .andExpect(status().isForbidden());
    }

    @DisplayName("쓰기 요청도 같은 경계를 지난다. 거절될 때 아무것도 만들어지지 않는다.")
    @Test
    void rejectsCustomerWrite() throws Exception {
        mvc.perform(post("/api-admin/v1/brands")
                .with(user("customer").roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"무신사\",\"description\":\"패션 플랫폼\"}"))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api-admin/v1/brands")
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"무신사\",\"description\":\"패션 플랫폼\"}"))
            .andExpect(status().isCreated());
    }

    @DisplayName("자격 증명을 보내면 경계를 지난다 — 주체를 주입하는 것이 아니라 실제로 인증한다.")
    @Test
    void allowsAdminWithCredentials() throws Exception {
        mvc.perform(get("/api-admin/v1/brands/1").with(httpBasic("admin", "local-only-not-a-secret")))
            .andExpect(status().isNotFound());
    }

    @DisplayName("자격 증명이 틀리면 거절된다. 이때도 401 이 아니라 403 이다 — 인증 창을 띄우지 않는다.")
    @Test
    void rejectsWrongCredentials() throws Exception {
        mvc.perform(get("/api-admin/v1/brands/1").with(httpBasic("admin", "틀린-비밀번호")))
            .andExpect(status().isForbidden());
    }

    @DisplayName("쓰기에 CSRF 토큰이 필요하지 않다 — 자격 증명이 요청마다 실려 오므로 세션이 없다.")
    @Test
    void writesWithoutCsrfToken() throws Exception {
        mvc.perform(post("/api-admin/v1/brands")
                .with(httpBasic("admin", "local-only-not-a-secret"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"무신사\",\"description\":\"패션 플랫폼\"}"))
            .andExpect(status().isCreated());
    }

    @DisplayName("고객 경로는 이 경계와 무관하다.")
    @Test
    void leavesCustomerPathsOpen() throws Exception {
        mvc.perform(get("/api/v1/brands/1"))
            .andExpect(status().isNotFound());
    }
}
