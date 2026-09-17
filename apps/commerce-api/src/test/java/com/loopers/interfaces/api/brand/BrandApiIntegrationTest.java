package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.brand.port.BrandRepository;
import com.loopers.domain.brand.Brand;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BrandApiIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserJpaRepository users;
    @Autowired private BrandJpaRepository brands;
    @Autowired private BrandRepository repository;
    @Autowired private BrandApplicationService service;
    @Autowired private DatabaseCleanUp cleanUp;

    @BeforeEach
    void prepareUser() {
        users.save(new UserJpaEntity(1L));
    }

    @AfterEach
    void clearDatabase() {
        cleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("관리자가 생성한 브랜드를 고객이 조회하면 저장된 ID와 이름을 반환한다")
    void createsAndReadsBrand() throws Exception {
        var response = mvc.perform(post("/api-admin/v1/brands")
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"브랜드\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.meta.result").value("SUCCESS"))
            .andReturn().getResponse();
        long id = mapper.readTree(response.getContentAsString()).path("data").path("id").asLong();
        mvc.perform(get("/api/v1/brands/{id}", id).header("X-USER-ID", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.name").value("브랜드"))
            .andExpect(jsonPath("$.data.description").doesNotExist());
        assertThat(brands.count()).isEqualTo(1);
    }

    @ParameterizedTest(name = "본문={0}")
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\" \"}", "{\"name\":3}", "{"})
    @DisplayName("잘못된 생성 입력은 400으로 거절하고 기존 브랜드를 유지한다")
    void rejectsInvalidInput(String body) throws Exception {
        var existing = service.create("기존");
        mvc.perform(post("/api-admin/v1/brands").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.meta.result").value("FAIL"));
        assertThat(brands.count()).isEqualTo(1);
        assertThat(service.getBrand(existing.id())).isEqualTo(existing);
    }

    @Test
    @DisplayName("일반 사용자와 미식별 관리 요청은 유효한 CSRF가 있어도 403이며 저장하지 않는다")
    void rejectsNonAdmin() throws Exception {
        mvc.perform(post("/api-admin/v1/brands").with(user("customer").roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"브랜드\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api-admin/v1/brands").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"브랜드\"}"))
            .andExpect(status().isForbidden());
        assertThat(brands.count()).isZero();
    }

    @Test
    @DisplayName("고객 식별 누락과 없는 사용자는 401로 거절한다")
    void rejectsUnknownUser() throws Exception {
        long id = service.create("브랜드").id().value();
        mvc.perform(get("/api/v1/brands/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/brands/{id}", id).header("X-USER-ID", "999"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("없는 브랜드와 삭제된 브랜드의 상세 조회는 동일한 404 오류다")
    void rejectsUnavailableBrand() throws Exception {
        Brand saved = repository.save(Brand.create("삭제 브랜드"));
        repository.save(Brand.restore(saved.getId(), saved.getName(), true));
        for (long id : new long[] {saved.getId().value(), 999L}) {
            mvc.perform(get("/api/v1/brands/{id}", id).header("X-USER-ID", "1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.meta.errorCode").value("Not Found"));
        }
        assertThat(repository.findById(saved.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("숫자가 아닌 브랜드 ID와 사용자 ID는 400으로 거절한다")
    void rejectsMalformedIds() throws Exception {
        mvc.perform(get("/api/v1/brands/abc").header("X-USER-ID", "1"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/brands/1").header("X-USER-ID", "abc"))
            .andExpect(status().isBadRequest());
    }
}
