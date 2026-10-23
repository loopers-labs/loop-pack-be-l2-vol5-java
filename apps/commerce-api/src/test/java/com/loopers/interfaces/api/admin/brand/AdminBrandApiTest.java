package com.loopers.interfaces.api.admin.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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
class AdminBrandApiTest {

    private static final String BRANDS = "/api-admin/v1/brands";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private Brand saveBrand(String name) {
        return brandRepository.save(Brand.create(name));
    }

    private Brand saveDeletedBrand(String name) {
        Brand brand = Brand.create(name);
        brand.delete();
        return brandRepository.save(brand);
    }

    private String storedName(Long brandId) {
        return brandRepository.findActive(brandId).orElseThrow().getName();
    }

    private long storedCount() {
        return brandRepository.findAllActive(PageRequest.of(0, 10)).getTotalElements();
    }

    private String nameBody(String name) {
        return "{\"name\":\"" + name + "\"}";
    }

    @DisplayName("POST /api-admin/v1/brands")
    @Nested
    class Create {
        @DisplayName("관리자가 등록하면 공백을 제거한 이름으로 저장하고, 생성·수정 시각을 포함해 응답한다.")
        @Test
        void savesBrand_andReturnsFields() throws Exception {
            mvc.perform(post(BRANDS).with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(nameBody("  루퍼스  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.name").value("루퍼스"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists());

            assertThat(storedCount()).isEqualTo(1);
        }

        @DisplayName("이름이 없거나 공백뿐이거나 50자를 넘으면 400 INVALID_INPUT 이고, 저장하지 않는다.")
        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"   \"}", "{\"name\":\"\"}"})
        void rejectsWithInvalidInput_whenNameIsInvalid(String body) throws Exception {
            mvc.perform(post(BRANDS).with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));

            assertThat(storedCount()).isZero();
        }

        @DisplayName("요청 본문이 없으면 400 INVALID_INPUT 이다.")
        @Test
        void rejectsWithInvalidInput_whenBodyIsMissing() throws Exception {
            mvc.perform(post(BRANDS).with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
        }
    }

    @DisplayName("GET /api-admin/v1/brands/{brandId}")
    @Nested
    class GetDetail {
        @DisplayName("관리자가 조회하면 이름과 생성·수정 시각을 응답한다.")
        @Test
        void returnsBrand() throws Exception {
            Brand brand = saveBrand("루퍼스");

            mvc.perform(get(BRANDS + "/" + brand.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(brand.getId()))
                .andExpect(jsonPath("$.data.name").value("루퍼스"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists());
        }

        @DisplayName("없는 브랜드이면 404 NOT_FOUND 이다.")
        @Test
        void returnsNotFound_whenBrandDoesNotExist() throws Exception {
            mvc.perform(get(BRANDS + "/999").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.meta.message").value("존재하지 않는 브랜드입니다."));
        }
    }

    @DisplayName("GET /api-admin/v1/brands")
    @Nested
    class GetList {
        @DisplayName("조건 없이 조회하면 첫 페이지를 20개 단위로, 삭제된 브랜드를 빼고 최신순으로 응답한다.")
        @Test
        void returnsDefaultPage_withoutDeleted_newestFirst() throws Exception {
            Brand first = saveBrand("첫째");
            saveDeletedBrand("삭제됨");
            Brand third = saveBrand("셋째");

            mvc.perform(get(BRANDS).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(third.getId()))
                .andExpect(jsonPath("$.data.content[1].id").value(first.getId()))
                .andExpect(jsonPath("$.data.content[0].createdAt").exists());
        }
    }

    @DisplayName("PUT /api-admin/v1/brands/{brandId}")
    @Nested
    class Update {
        @DisplayName("관리자가 수정하면 이름이 바뀌고 저장된다.")
        @Test
        void changesName_andPersists() throws Exception {
            Brand brand = saveBrand("루퍼스");

            mvc.perform(put(BRANDS + "/" + brand.getId()).with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(nameBody("  새이름 ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새이름"));

            assertThat(storedName(brand.getId())).isEqualTo("새이름");
        }

        @DisplayName("이름이 없으면 브랜드가 존재하지 않아도 404 가 아니라 400 INVALID_INPUT 이다. 필수 누락은 조회 전에 거절한다.")
        @Test
        void rejectsWithInvalidInput_beforeLookup_whenNameIsMissing() throws Exception {
            mvc.perform(put(BRANDS + "/999").with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
        }
    }

    @DisplayName("DELETE /api-admin/v1/brands/{brandId}")
    @Nested
    class Delete {
        @DisplayName("관리자가 삭제하면 200 성공으로 응답하고, 이후 조회되지 않는다.")
        @Test
        void deletesBrand() throws Exception {
            Brand brand = saveBrand("루퍼스");

            mvc.perform(delete(BRANDS + "/" + brand.getId()).with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"));

            mvc.perform(get(BRANDS + "/" + brand.getId()).with(admin()))
                .andExpect(status().isNotFound());
            assertThat(storedCount()).isZero();
        }

        @DisplayName("삭제되지 않은 상품이 연결되어 있으면 재고가 0이어도 200 성공이고, 연결 상품도 함께 삭제된다.")
        @Test
        void deletesBrandWithLinkedProducts() throws Exception {
            Brand brand = saveBrand("루퍼스");
            Product soldOut = productRepository.save(
                Product.create(brand.getId(), "품절 상품", new Money(89_000), new Stock(0)));

            mvc.perform(delete(BRANDS + "/" + brand.getId()).with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"));

            mvc.perform(get(BRANDS + "/" + brand.getId()).with(admin()))
                .andExpect(status().isNotFound());
            mvc.perform(get("/api-admin/v1/products/" + soldOut.getId()).with(admin()))
                .andExpect(status().isNotFound());
        }
    }
}
