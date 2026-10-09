package com.loopers.config;

import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(MySqlTestContainersConfig.class)
class LocalSwaggerModeHttpTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @DisplayName("로컬 Swagger 관리자 인증과 일반 사용자 ID를 각각 해당 API에 적용한다.")
    @Test
    void supportsAdminAndCustomerModes() throws Exception {
        mockMvc.perform(get("/api-admin/v1/brands"))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api-admin/v1/brands")
                .with(httpBasic("admin", "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nike\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value("Nike"));

        User customer = new CommerceFixture(entityManager, transactionManager).user();
        mockMvc.perform(get("/api/v1/points")
                .header("X-USER-ID", customer.getId()))
            .andExpect(status().isOk());
    }
}
