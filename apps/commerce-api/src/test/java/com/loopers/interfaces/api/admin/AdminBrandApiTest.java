package com.loopers.interfaces.api.admin;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
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
import org.springframework.test.web.servlet.MockMvc;

import static com.loopers.interfaces.api.admin.AdminRequests.adminDelete;
import static com.loopers.interfaces.api.admin.AdminRequests.adminGet;
import static com.loopers.interfaces.api.admin.AdminRequests.adminPost;
import static com.loopers.interfaces.api.admin.AdminRequests.adminPut;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminBrandApiTest {

    private static final String BRANDS = "/api-admin/v1/brands";

    @Autowired
    private MockMvc mvc;

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

    private Brand deletedBrand(String name) {
        Brand brand = new Brand(name);
        brand.delete();
        return brandJpaRepository.save(brand);
    }

    @DisplayName("POST /api-admin/v1/brands")
    @Nested
    class Create {

        @DisplayName("유효한 이름이면, 등록된 관리자 브랜드(등록·수정 시각 포함)를 반환한다.")
        @Test
        void returnsAdminBrand_whenNameIsValid() throws Exception {
            mvc.perform(adminPost(BRANDS, "{\"name\": \"루퍼스\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brandId").value(notNullValue()))
                .andExpect(jsonPath("$.data.name").value("루퍼스"))
                .andExpect(jsonPath("$.data.createdAt").value(notNullValue()))
                .andExpect(jsonPath("$.data.updatedAt").value(notNullValue()));

            assertThat(brandJpaRepository.count()).isEqualTo(1L);
        }

        @DisplayName("이름이 누락·빈 값·100자 초과면, 400 BAD_REQUEST 응답을 받고 저장되지 않는다.")
        @Test
        void returnsBadRequest_whenNameIsInvalid() throws Exception {
            for (String body : new String[] {"{}", "{\"name\": \" \"}", "{\"name\": \"" + "가".repeat(101) + "\"}"}) {
                mvc.perform(adminPost(BRANDS, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.result").value("FAIL"));
            }

            assertThat(brandJpaRepository.count()).isZero();
        }
    }

    @DisplayName("GET /api-admin/v1/brands, GET /api-admin/v1/brands/{brandId}")
    @Nested
    class Read {

        @DisplayName("목록은 삭제된 브랜드를 제외하고 최신순으로 페이지 조회한다.")
        @Test
        void returnsActiveBrandsInLatestOrder() throws Exception {
            // arrange
            Brand first = brandJpaRepository.save(new Brand("A"));
            Brand second = brandJpaRepository.save(new Brand("B"));
            deletedBrand("삭제됨");

            // act & assert
            mvc.perform(adminGet(BRANDS).param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].brandId").value(contains(second.getId().intValue(), first.getId().intValue())))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @DisplayName("목록의 page·size가 잘못되면, 400 BAD_REQUEST 응답을 받는다.")
        @Test
        void returnsBadRequest_whenPageIsInvalid() throws Exception {
            mvc.perform(adminGet(BRANDS).param("size", "101"))
                .andExpect(status().isBadRequest());
        }

        @DisplayName("상세는 관리자 브랜드를 반환하고, 없거나 삭제된 브랜드면 404 NOT_FOUND 응답을 받는다.")
        @Test
        void returnsAdminBrand_orNotFound() throws Exception {
            // arrange
            Brand brand = brandJpaRepository.save(new Brand("루퍼스"));
            Brand deleted = deletedBrand("삭제됨");

            // act & assert
            mvc.perform(adminGet(BRANDS + "/" + brand.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("루퍼스"))
                .andExpect(jsonPath("$.data.createdAt").value(notNullValue()));
            mvc.perform(adminGet(BRANDS + "/" + deleted.getId())).andExpect(status().isNotFound());
            mvc.perform(adminGet(BRANDS + "/999999")).andExpect(status().isNotFound());
        }
    }

    @DisplayName("PUT /api-admin/v1/brands/{brandId}")
    @Nested
    class Update {

        @DisplayName("이름을 수정하면, 고객 브랜드 조회에도 반영된다. (QRY-002)")
        @Test
        void updatesName_andReflectsToCustomer() throws Exception {
            // arrange
            Brand brand = brandJpaRepository.save(new Brand("루퍼스"));

            // act
            mvc.perform(adminPut(BRANDS + "/" + brand.getId(), "{\"name\": \"새 루퍼스\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새 루퍼스"));

            // assert
            mvc.perform(get("/api/v1/brands/" + brand.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새 루퍼스"))
                .andExpect(jsonPath("$.data.createdAt").doesNotExist());
        }

        @DisplayName("이름이 잘못되면 400, 삭제된 브랜드면 404 응답을 받고 이름이 유지된다.")
        @Test
        void returnsError_andKeepsName() throws Exception {
            // arrange
            Brand brand = brandJpaRepository.save(new Brand("루퍼스"));
            Brand deleted = deletedBrand("삭제됨");

            // act
            mvc.perform(adminPut(BRANDS + "/" + brand.getId(), "{\"name\": \"\"}"))
                .andExpect(status().isBadRequest());
            mvc.perform(adminPut(BRANDS + "/" + deleted.getId(), "{\"name\": \"변경\"}"))
                .andExpect(status().isNotFound());

            // assert
            assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getName()).isEqualTo("루퍼스");
            assertThat(brandJpaRepository.findById(deleted.getId()).orElseThrow().getName()).isEqualTo("삭제됨");
        }
    }

    @DisplayName("DELETE /api-admin/v1/brands/{brandId}")
    @Nested
    class Delete {

        @DisplayName("연결된 상품이 모두 삭제된 브랜드는 삭제되어 data가 null이고, 고객 조회에서 404가 된다. (DEL-001)")
        @Test
        void deletesBrand_whenAllProductsAreDeleted() throws Exception {
            // arrange
            Brand brand = brandJpaRepository.save(new Brand("루퍼스"));
            Product product = new Product(brand.getId(), "가방", 3_000L, 1L);
            product.delete();
            productJpaRepository.save(product);

            // act
            mvc.perform(adminDelete(BRANDS + "/" + brand.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

            // assert
            assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getDeletedAt()).isNotNull();
            mvc.perform(get("/api/v1/brands/" + brand.getId())).andExpect(status().isNotFound());
        }

        @DisplayName("재고 0인 미삭제 상품이 남은 브랜드는, 409 CONFLICT 응답을 받고 브랜드가 유지된다. (DEL-001)")
        @Test
        void returnsConflict_whenActiveProductWithZeroStockRemains() throws Exception {
            // arrange
            Brand brand = brandJpaRepository.save(new Brand("루퍼스"));
            productJpaRepository.save(new Product(brand.getId(), "가방", 3_000L, 0L));

            // act
            mvc.perform(adminDelete(BRANDS + "/" + brand.getId()))
                .andExpect(status().isConflict());

            // assert
            assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getDeletedAt()).isNull();
        }

        @DisplayName("없거나 이미 삭제된 브랜드면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void returnsNotFound_whenBrandDoesNotExistOrIsDeleted() throws Exception {
            // arrange
            Brand deleted = deletedBrand("삭제됨");

            // act & assert
            mvc.perform(adminDelete(BRANDS + "/999999")).andExpect(status().isNotFound());
            mvc.perform(adminDelete(BRANDS + "/" + deleted.getId())).andExpect(status().isNotFound());
        }
    }
}
