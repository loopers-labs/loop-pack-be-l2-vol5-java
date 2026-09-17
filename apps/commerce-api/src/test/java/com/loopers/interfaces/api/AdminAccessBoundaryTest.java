package com.loopers.interfaces.api;

import com.loopers.domain.user.UserModel;
import com.loopers.fixture.UserFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("관리자 API 는 ADMIN 역할이 있는 요청만 통과시킨다.")
@SpringBootTest
@AutoConfigureMockMvc
class AdminAccessBoundaryTest {

    private static final String ADMIN_ENDPOINT = "/api-admin/v1/brands";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("식별되지 않은 요청은 403 으로 거절한다.")
    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT))
            .andExpect(status().isForbidden());
    }

    @DisplayName("ADMIN 역할이 없는 사용자는 403 으로 거절한다.")
    @WithMockUser(roles = "USER")
    @Test
    void rejectsNonAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT))
            .andExpect(status().isForbidden());
    }

    @DisplayName("ADMIN 역할이 있는 요청은 통과시킨다.")
    @WithMockUser(roles = "ADMIN")
    @Test
    void allowsAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT))
            .andExpect(status().isOk());
    }

    @DisplayName("유효한 CSRF 입력을 넣어도 ADMIN 역할이 없으면 변경 요청을 403 으로 거절한다.")
    @WithMockUser(roles = "USER")
    @Test
    void rejectsNonAdminMutationWithCsrf() throws Exception {
        mockMvc.perform(post(ADMIN_ENDPOINT).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"나이키\"}"))
            .andExpect(status().isForbidden());
    }

    @DisplayName("유효한 CSRF 입력을 넣어도 식별되지 않은 변경 요청은 403 으로 거절한다.")
    @Test
    void rejectsUnauthenticatedMutationWithCsrf() throws Exception {
        mockMvc.perform(post(ADMIN_ENDPOINT).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"나이키\"}"))
            .andExpect(status().isForbidden());
    }

    @DisplayName("고객 API 는 관리자 경계의 영향을 받지 않고 기존 요청자 식별 규칙을 유지한다.")
    @Test
    void keepsCustomerApiContract() throws Exception {
        UserModel user = userFixture.createUserWithPoint();

        mockMvc.perform(get("/api/v1/points").header("X-USER-ID", String.valueOf(user.getId())))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/points"))
            .andExpect(status().isBadRequest());
    }
}
