package com.loopers.interfaces.api.admin;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminBoundaryMockMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void allowsAdminRequest() throws Exception {
        mvc.perform(get("/api-admin/v1/brands").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk());
    }

    @Test
    void rejectsGeneralUserRequest() throws Exception {
        mvc.perform(get("/api-admin/v1/brands").with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mvc.perform(get("/api-admin/v1/brands"))
            .andExpect(status().isForbidden());
    }

    @Test
    void allowsAdminChangeRequestWithCsrf() throws Exception {
        mvc.perform(post("/api-admin/v1/brands")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"Nike\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void rejectsGeneralUserChangeRequestWithCsrf() throws Exception {
        mvc.perform(post("/api-admin/v1/brands")
                .with(user("customer").roles("USER"))
                .with(csrf())
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"Nike\"}"))
            .andExpect(status().isForbidden());
    }
}
