package com.loopers.interfaces.api.admin.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BrandAdminV1ApiE2ETest {

    private static final String ENDPOINT = "/api-admin/v1/brands";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private BrandModel savedDeletedBrand(String name) {
        BrandModel brand = new BrandModel(name, null);
        brand.delete();
        return brandJpaRepository.save(brand);
    }

    @DisplayName("POST /api-admin/v1/brands")
    @Nested
    class Create {

        @DisplayName("BRD-01 유효한 이름·설명이면 저장하고 관리자 응답(시각 포함)을 돌려준다.")
        @Test
        void createsBrand() throws Exception {
            // act
            mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", " 나이키 ", "description", "스포츠 브랜드"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("나이키"))
                .andExpect(jsonPath("$.data.createdAt").exists());

            // assert
            assertThat(brandJpaRepository.findAll()).extracting(BrandModel::getName).containsExactly("나이키");
        }

        @DisplayName("BRD-01 공백뿐인 이름이면 400이고, 저장되지 않는다.")
        @Test
        void rejectsBlankName() throws Exception {
            // act
            mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("Bad Request"));

            // assert
            assertThat(brandJpaRepository.count()).isZero();
        }

        @DisplayName("ROLE_USER 요청은 403이고, 저장되지 않는다.")
        @Test
        void forbidsNonAdmin() throws Exception {
            // act
            mockMvc.perform(post(ENDPOINT).with(user("customer").roles("USER")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "나이키"))))
                .andExpect(status().isForbidden());

            // assert
            assertThat(brandJpaRepository.count()).isZero();
        }
    }

    @DisplayName("GET /api-admin/v1/brands")
    @Nested
    class GetList {

        @DisplayName("BRD-03 삭제된 브랜드를 빼고 최신 등록순으로 돌려준다.")
        @Test
        void returnsActiveBrandsNewestFirst() throws Exception {
            // arrange
            BrandModel first = brandJpaRepository.save(new BrandModel("나이키", null));
            savedDeletedBrand("아디다스");
            BrandModel third = brandJpaRepository.save(new BrandModel("뉴발란스", null));

            // act & assert
            mockMvc.perform(get(ENDPOINT).with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(third.getId()))
                .andExpect(jsonPath("$.data.content[1].id").value(first.getId()))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
        }

        @DisplayName("P-08 page가 음수이거나 size가 100을 넘으면 400이다.")
        @Test
        void rejectsInvalidPage() throws Exception {
            // act & assert
            mockMvc.perform(get(ENDPOINT).param("page", "-1").with(ADMIN))
                .andExpect(status().isBadRequest());
            mockMvc.perform(get(ENDPOINT).param("size", "101").with(ADMIN))
                .andExpect(status().isBadRequest());
        }
    }

    @DisplayName("GET /api-admin/v1/brands/{brandId}")
    @Nested
    class GetDetail {

        @DisplayName("존재하는 브랜드면 상세를 돌려준다.")
        @Test
        void returnsBrand() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드"));

            // act & assert
            mockMvc.perform(get(ENDPOINT + "/" + brand.getId()).with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("나이키"))
                .andExpect(jsonPath("$.data.description").value("스포츠 브랜드"));
        }

        @DisplayName("BRD-03·P-10 삭제된 브랜드면 404다.")
        @Test
        void returnsNotFound_whenDeleted() throws Exception {
            // arrange
            BrandModel deleted = savedDeletedBrand("아디다스");

            // act & assert
            mockMvc.perform(get(ENDPOINT + "/" + deleted.getId()).with(ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("Not Found"));
        }
    }

    @DisplayName("PUT /api-admin/v1/brands/{brandId}")
    @Nested
    class Update {

        @DisplayName("BRD-01 유효한 값이면 이름·설명을 바꾸고 저장한다.")
        @Test
        void updatesBrand() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드"));

            // act
            mockMvc.perform(put(ENDPOINT + "/" + brand.getId()).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "나이키 코리아", "description", "변경된 설명"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("나이키 코리아"));

            // assert
            BrandModel reloaded = brandJpaRepository.findById(brand.getId()).orElseThrow();
            assertThat(reloaded.getName()).isEqualTo("나이키 코리아");
            assertThat(reloaded.getDescription()).isEqualTo("변경된 설명");
        }

        @DisplayName("BRD-01 유효하지 않은 이름이면 400이고, 저장된 값은 그대로다.")
        @Test
        void rejectsInvalidUpdate_andKeepsStoredValues() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드"));

            // act
            mockMvc.perform(put(ENDPOINT + "/" + brand.getId()).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "가".repeat(51)))))
                .andExpect(status().isBadRequest());

            // assert
            BrandModel reloaded = brandJpaRepository.findById(brand.getId()).orElseThrow();
            assertThat(reloaded.getName()).isEqualTo("나이키");
            assertThat(reloaded.getDescription()).isEqualTo("스포츠 브랜드");
        }

        @DisplayName("BRD-03 삭제된 브랜드는 수정할 수 없어 404다.")
        @Test
        void returnsNotFound_whenDeleted() throws Exception {
            // arrange
            BrandModel deleted = savedDeletedBrand("아디다스");

            // act & assert
            mockMvc.perform(put(ENDPOINT + "/" + deleted.getId()).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "리복"))))
                .andExpect(status().isNotFound());
            assertThat(brandJpaRepository.findById(deleted.getId()).orElseThrow().getName()).isEqualTo("아디다스");
        }
    }

    @DisplayName("DELETE /api-admin/v1/brands/{brandId}")
    @Nested
    class Delete {

        @DisplayName("BRD-02 재고 0인 살아 있는 상품이 연결돼 있으면 409이고, 브랜드는 삭제되지 않는다.")
        @Test
        void rejectsDelete_whenActiveProductRemains() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", null));
            productJpaRepository.save(new ProductModel(brand.getId(), "품절 상품", 1_000, 0));

            // act
            mockMvc.perform(delete(ENDPOINT + "/" + brand.getId()).with(ADMIN).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("Conflict"));

            // assert
            assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getDeletedAt()).isNull();
        }

        @DisplayName("BRD-02 연결된 상품이 모두 삭제됐으면 브랜드를 삭제하고, 이후 조회는 404다.")
        @Test
        void deletesBrand_whenAllProductsDeleted() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", null));
            ProductModel product = new ProductModel(brand.getId(), "단종 상품", 1_000, 3);
            product.delete();
            productJpaRepository.save(product);

            // act
            mockMvc.perform(delete(ENDPOINT + "/" + brand.getId()).with(ADMIN).with(csrf()))
                .andExpect(status().isOk());

            // assert
            assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getDeletedAt()).isNotNull();
            mockMvc.perform(get(ENDPOINT + "/" + brand.getId()).with(ADMIN))
                .andExpect(status().isNotFound());
        }
    }
}
