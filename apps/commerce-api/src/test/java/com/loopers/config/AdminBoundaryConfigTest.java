package com.loopers.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminBoundaryConfigTest {

    @Autowired
    private MockMvc mvc;

    @DisplayName("관리자 경로(/api-admin/**)에 요청하면, ")
    @Nested
    class AdminBoundary {

        @DisplayName("ADMIN 권한을 가진 사용자가 요청하면, 통과된다.")
        @Test
        void allowsAccess_whenRequestedByAdminUser() throws Exception {
            mvc.perform(get("/api-admin/v1/brands").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        }

        @DisplayName("ADMIN 권한이 없는 사용자가 요청하면, 403을 응답한다.")
        @Test
        void returns403_whenRequestedByNonAdminUser() throws Exception {
            mvc.perform(get("/api-admin/v1/brands").with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
        }

        @DisplayName("식별되지 않은 사용자가 요청하면, 403을 응답한다.")
        @Test
        void returns403_whenRequestedByUnauthenticatedUser() throws Exception {
            mvc.perform(get("/api-admin/v1/brands"))
                .andExpect(status().isForbidden());
        }
    }
}
