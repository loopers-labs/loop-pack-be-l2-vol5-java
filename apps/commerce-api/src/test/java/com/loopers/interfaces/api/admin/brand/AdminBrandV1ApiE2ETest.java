package com.loopers.interfaces.api.admin.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AdminBrandV1ApiE2ETest {

    private static final String ENDPOINT_CREATE = "/api-admin/v1/brands";

    private final MockMvc mockMvc;
    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ObjectMapper objectMapper;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public AdminBrandV1ApiE2ETest(
        MockMvc mockMvc,
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ObjectMapper objectMapper,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.mockMvc = mockMvc;
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.objectMapper = objectMapper;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api-admin/v1/brands")
    @Nested
    class CreateBrand {
        @DisplayName("관리자가 유효한 이름으로 요청하면, 201 응답과 함께 브랜드가 저장된다.")
        @Test
        void returnsCreatedAndSavesBrand_whenRequesterIsAdmin() throws Exception {
            // arrange
            String body = objectMapper.writeValueAsString(new AdminBrandV1Dto.CreateRequest("루퍼스"));

            // act
            mockMvc.perform(post(ENDPOINT_CREATE)
                    .with(user("admin").roles("ADMIN")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isCreated());

            // assert
            List<Brand> saved = brandJpaRepository.findAll();
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getName()).isEqualTo("루퍼스");
        }

        @DisplayName("관리자가 빈 이름으로 요청하면, 400 응답을 받고 저장되지 않는다.")
        @Test
        void returnsBadRequestAndSavesNothing_whenNameIsBlank() throws Exception {
            // arrange
            String body = objectMapper.writeValueAsString(new AdminBrandV1Dto.CreateRequest("   "));

            // act
            mockMvc.perform(post(ENDPOINT_CREATE)
                    .with(user("admin").roles("ADMIN")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());

            // assert
            assertThat(brandJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("식별되지 않은 요청이면, 403 응답을 받고 저장되지 않는다.")
        @Test
        void returnsForbiddenAndSavesNothing_whenRequesterIsAnonymous() {
            // arrange
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>("{\"name\":\"루퍼스\"}", headers);

            // act
            ResponseEntity<String> response =
                testRestTemplate.exchange(ENDPOINT_CREATE, HttpMethod.POST, request, String.class);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(brandJpaRepository.findAll()).isEmpty();
        }
    }
}
