package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.ProductFixture;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("관리자 브랜드 API 는 브랜드를 등록·조회·수정·삭제한다.")
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class BrandAdminV1ApiTest {

    private static final String ENDPOINT = "/api-admin/v1/brands";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BrandFixture brandFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private BrandJpaRepository brandJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @DisplayName("GET /api-admin/v1/brands")
    @Nested
    class GetBrands {
        @DisplayName("삭제된 브랜드를 제외하고 최신순 페이지로 반환한다.")
        @Test
        void returnsActiveBrandPage() throws Exception {
            brandFixture.createBrand("첫째");
            brandFixture.createBrand("둘째");
            brandFixture.createDeletedBrand("삭제됨");

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].name").value("둘째"))
                .andExpect(jsonPath("$.data.items[1].name").value("첫째"));
        }

        @DisplayName("[잠정] page·size·sort=oldest 로 끊어 반환한다.")
        @Test
        void returnsRequestedPage() throws Exception {
            brandFixture.createBrand("첫째");
            brandFixture.createBrand("둘째");
            brandFixture.createBrand("셋째");

            mockMvc.perform(get(ENDPOINT).param("page", "0").param("size", "2").param("sort", "oldest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].name").value("첫째"))
                .andExpect(jsonPath("$.data.items[1].name").value("둘째"));
        }

        @DisplayName("지원하지 않는 sort 는 400 INVALID_SORT 로 거절한다.")
        @Test
        void rejectsUnsupportedSort() throws Exception {
            mockMvc.perform(get(ENDPOINT).param("sort", "likes_desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_SORT"));
        }
    }

    @DisplayName("POST /api-admin/v1/brands")
    @Nested
    class CreateBrand {
        @DisplayName("브랜드를 저장하고 201 로 반환한다.")
        @Test
        void createsBrand() throws Exception {
            mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "나이키"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.name").value("나이키"));

            assertThat(brandJpaRepository.findAll()).hasSize(1);
        }

        @DisplayName("이름이 비어 있으면 400 INVALID_BRAND_NAME 으로 거절하고 저장하지 않는다.")
        @Test
        void rejectsBlankName() throws Exception {
            mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_BRAND_NAME"));

            assertThat(brandJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("name 이 없으면 400 INVALID_BRAND_NAME 으로 거절한다.")
        @Test
        void rejectsMissingName() throws Exception {
            mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_BRAND_NAME"));
        }
    }

    @DisplayName("GET /api-admin/v1/brands/{brandId}")
    @Nested
    class GetBrand {
        @DisplayName("활성 브랜드 상세를 반환한다.")
        @Test
        void returnsBrand() throws Exception {
            BrandModel nike = brandFixture.createBrand("나이키");

            mockMvc.perform(get(ENDPOINT + "/" + nike.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(nike.getId()))
                .andExpect(jsonPath("$.data.name").value("나이키"));
        }

        @DisplayName("[잠정] 삭제된 브랜드는 404 BRAND_NOT_FOUND 로 응답한다.")
        @Test
        void rejectsDeletedBrand() throws Exception {
            BrandModel deleted = brandFixture.createDeletedBrand("사라진브랜드");

            mockMvc.perform(get(ENDPOINT + "/" + deleted.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        }
    }

    @DisplayName("PUT /api-admin/v1/brands/{brandId}")
    @Nested
    class UpdateBrand {
        @DisplayName("이름을 수정해 저장하고 200 으로 반환한다.")
        @Test
        void updatesBrand() throws Exception {
            BrandModel nike = brandFixture.createBrand("나이키");

            mockMvc.perform(put(ENDPOINT + "/" + nike.getId()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "나이키 코리아"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("나이키 코리아"));

            assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getName()).isEqualTo("나이키 코리아");
        }

        @DisplayName("잘못된 이름은 400 INVALID_BRAND_NAME 으로 거절하고 기존 이름을 유지한다.")
        @Test
        void rejectsInvalidName() throws Exception {
            BrandModel nike = brandFixture.createBrand("나이키");

            mockMvc.perform(put(ENDPOINT + "/" + nike.getId()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_BRAND_NAME"));

            assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getName()).isEqualTo("나이키");
        }

        @DisplayName("삭제된 브랜드는 404 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedBrand() throws Exception {
            BrandModel deleted = brandFixture.createDeletedBrand("사라진브랜드");

            mockMvc.perform(put(ENDPOINT + "/" + deleted.getId()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "새이름"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        }
    }

    @DisplayName("DELETE /api-admin/v1/brands/{brandId}")
    @Nested
    class DeleteBrand {
        @DisplayName("활성 상품이 없으면 삭제하고 200 과 빈 데이터로 응답한다.")
        @Test
        void deletesBrand() throws Exception {
            BrandModel nike = brandFixture.createBrand("나이키");

            mockMvc.perform(delete(ENDPOINT + "/" + nike.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

            assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNotNull();
        }

        @DisplayName("재고 0 인 활성 상품이 남아 있으면 409 BRAND_HAS_ACTIVE_PRODUCTS 로 거절하고 브랜드를 유지한다.")
        @Test
        void rejectsWhenActiveProductRemains() throws Exception {
            BrandModel nike = brandFixture.createBrand("나이키");
            productFixture.createProduct(nike.getId(), "품절 운동화", 10_000L, 0L);

            mockMvc.perform(delete(ENDPOINT + "/" + nike.getId()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_HAS_ACTIVE_PRODUCTS"));

            assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNull();
        }

        @DisplayName("이미 삭제된 브랜드는 404 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedBrand() throws Exception {
            BrandModel deleted = brandFixture.createDeletedBrand("사라진브랜드");

            mockMvc.perform(delete(ENDPOINT + "/" + deleted.getId()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        }
    }
}
