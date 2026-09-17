package com.loopers.interfaces.api.admin;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class AdminProductApiTest {

    private static final String PRODUCTS = "/api-admin/v1/products";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Brand brand;

    @BeforeEach
    void setUp() {
        brand = brandJpaRepository.save(new Brand("루퍼스"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product saveProduct(Long brandId, String name) {
        return productJpaRepository.save(new Product(brandId, name, 10_000L, 10L));
    }

    private Product deletedProduct() {
        Product product = new Product(brand.getId(), "삭제됨", 10_000L, 10L);
        product.delete();
        return productJpaRepository.save(product);
    }

    private Product reload(Product product) {
        return productJpaRepository.findById(product.getId()).orElseThrow();
    }

    private String createBody(Long brandId, String name, long price, long stock) {
        return "{\"brandId\": " + brandId + ", \"name\": \"" + name + "\", \"price\": " + price + ", \"stock\": " + stock + "}";
    }

    @DisplayName("POST /api-admin/v1/products")
    @Nested
    class Create {

        @DisplayName("유효한 값이면, 재고·등록·수정 시각을 포함한 관리자 상품을 반환한다.")
        @Test
        void returnsAdminProduct_whenValuesAreValid() throws Exception {
            mvc.perform(adminPost(PRODUCTS, createBody(brand.getId(), "가방", 30_000L, 5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(notNullValue()))
                .andExpect(jsonPath("$.data.brandId").value(brand.getId()))
                .andExpect(jsonPath("$.data.name").value("가방"))
                .andExpect(jsonPath("$.data.price").value(30_000))
                .andExpect(jsonPath("$.data.stock").value(5))
                .andExpect(jsonPath("$.data.createdAt").value(notNullValue()))
                .andExpect(jsonPath("$.data.updatedAt").value(notNullValue()));

            assertThat(productJpaRepository.count()).isEqualTo(1L);
        }

        @DisplayName("없거나 삭제된 브랜드면, 404 NOT_FOUND 응답을 받고 저장되지 않는다. (PRD-001)")
        @Test
        void returnsNotFound_whenBrandDoesNotExistOrIsDeleted() throws Exception {
            // arrange
            Brand deletedBrand = new Brand("삭제됨");
            deletedBrand.delete();
            Brand deleted = brandJpaRepository.save(deletedBrand);

            // act & assert
            mvc.perform(adminPost(PRODUCTS, createBody(999999L, "가방", 30_000L, 5L)))
                .andExpect(status().isNotFound());
            mvc.perform(adminPost(PRODUCTS, createBody(deleted.getId(), "가방", 30_000L, 5L)))
                .andExpect(status().isNotFound());
            assertThat(productJpaRepository.count()).isZero();
        }

        @DisplayName("이름 101자·가격 1,000만 원 초과·재고 음수면, 400 BAD_REQUEST 응답을 받고 저장되지 않는다. (P-3)")
        @Test
        void returnsBadRequest_whenValuesAreOutOfRange() throws Exception {
            String[] bodies = {
                createBody(brand.getId(), "가".repeat(101), 30_000L, 5L),
                createBody(brand.getId(), "가방", 10_000_001L, 5L),
                createBody(brand.getId(), "가방", 30_000L, -1L)
            };
            for (String body : bodies) {
                mvc.perform(adminPost(PRODUCTS, body))
                    .andExpect(status().isBadRequest());
            }

            assertThat(productJpaRepository.count()).isZero();
        }
    }

    @DisplayName("GET /api-admin/v1/products, GET /api-admin/v1/products/{productId}")
    @Nested
    class Read {

        @DisplayName("목록은 삭제된 상품을 제외하고 최신순으로 조회하며, brandId로 필터할 수 있다.")
        @Test
        void returnsActiveProductsInLatestOrder_withBrandFilter() throws Exception {
            // arrange
            Brand otherBrand = brandJpaRepository.save(new Brand("다른 브랜드"));
            Product first = saveProduct(brand.getId(), "가방");
            Product second = saveProduct(brand.getId(), "모자");
            Product other = saveProduct(otherBrand.getId(), "신발");
            deletedProduct();

            // act & assert
            mvc.perform(adminGet(PRODUCTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].productId")
                    .value(contains(other.getId().intValue(), second.getId().intValue(), first.getId().intValue())))
                .andExpect(jsonPath("$.data.content[0].stock").value(10));
            mvc.perform(adminGet(PRODUCTS).param("brandId", String.valueOf(brand.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].productId").value(contains(second.getId().intValue(), first.getId().intValue())))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @DisplayName("목록을 없는 brandId로 필터하면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void returnsNotFound_whenBrandFilterDoesNotExist() throws Exception {
            mvc.perform(adminGet(PRODUCTS).param("brandId", "999999"))
                .andExpect(status().isNotFound());
        }

        @DisplayName("상세는 관리자 상품을 반환하고, 삭제된 상품이면 404 NOT_FOUND 응답을 받는다.")
        @Test
        void returnsAdminProduct_orNotFound() throws Exception {
            // arrange
            Product product = saveProduct(brand.getId(), "가방");
            Product deleted = deletedProduct();

            // act & assert
            mvc.perform(adminGet(PRODUCTS + "/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stock").value(10))
                .andExpect(jsonPath("$.data.brandId").value(brand.getId()));
            mvc.perform(adminGet(PRODUCTS + "/" + deleted.getId())).andExpect(status().isNotFound());
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}")
    @Nested
    class Update {

        @DisplayName("가격을 10,000 → 12,000으로 수정하면, 고객 상세 응답에 반영되고 고객 응답에는 재고·시각이 없다. (QRY-002)")
        @Test
        void updatesPrice_andReflectsToCustomer() throws Exception {
            // arrange
            Product product = saveProduct(brand.getId(), "가방");

            // act
            mvc.perform(adminPut(PRODUCTS + "/" + product.getId(), "{\"name\": \"가방\", \"price\": 12000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(12_000));

            // assert
            mvc.perform(get("/api/v1/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(12_000))
                .andExpect(jsonPath("$.data.stock").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").doesNotExist());
        }

        @DisplayName("요청에 brandId·stock을 넣어도, 브랜드와 재고는 바뀌지 않는다. (PRD-001)")
        @Test
        void keepsBrandAndStock_whenRequestContainsThem() throws Exception {
            // arrange
            Brand otherBrand = brandJpaRepository.save(new Brand("다른 브랜드"));
            Product product = saveProduct(brand.getId(), "가방");

            // act
            mvc.perform(adminPut(PRODUCTS + "/" + product.getId(), "{\"brandId\": " + otherBrand.getId() + ", \"name\": \"새 가방\", \"price\": 12000, \"stock\": 99}"))
                .andExpect(status().isOk());

            // assert
            Product found = reload(product);
            assertThat(found.getBrandId()).isEqualTo(brand.getId());
            assertThat(found.getStock()).isEqualTo(10L);
            assertThat(found.getName()).isEqualTo("새 가방");
        }

        @DisplayName("가격 범위를 넘으면 400, 삭제된 상품이면 404 응답을 받고 값이 유지된다. (DEL-002)")
        @Test
        void returnsError_andKeepsValues() throws Exception {
            // arrange
            Product product = saveProduct(brand.getId(), "가방");
            Product deleted = deletedProduct();

            // act
            mvc.perform(adminPut(PRODUCTS + "/" + product.getId(), "{\"name\": \"가방\", \"price\": 10000001}"))
                .andExpect(status().isBadRequest());
            mvc.perform(adminPut(PRODUCTS + "/" + deleted.getId(), "{\"name\": \"변경\", \"price\": 1000}"))
                .andExpect(status().isNotFound());

            // assert
            assertThat(reload(product).getPrice()).isEqualTo(10_000L);
            assertThat(reload(deleted).getName()).isEqualTo("삭제됨");
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}/stock")
    @Nested
    class ChangeStock {

        @DisplayName("0 이상인 최종 수량이면, 그 값으로 설정된다. (STK-002)")
        @Test
        void setsStock_whenStockIsNotNegative() throws Exception {
            // arrange
            Product product = saveProduct(brand.getId(), "가방");

            // act & assert
            mvc.perform(adminPut(PRODUCTS + "/" + product.getId() + "/stock", "{\"stock\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stock").value(0));
            assertThat(reload(product).getStock()).isZero();
        }

        @DisplayName("음수·누락이면 400, 삭제된 상품이면 404 응답을 받고 재고가 유지된다. (STK-002, DEL-002)")
        @Test
        void returnsError_andKeepsStock() throws Exception {
            // arrange
            Product product = saveProduct(brand.getId(), "가방");
            Product deleted = deletedProduct();

            // act
            mvc.perform(adminPut(PRODUCTS + "/" + product.getId() + "/stock", "{\"stock\": -1}"))
                .andExpect(status().isBadRequest());
            mvc.perform(adminPut(PRODUCTS + "/" + product.getId() + "/stock", "{}"))
                .andExpect(status().isBadRequest());
            mvc.perform(adminPut(PRODUCTS + "/" + deleted.getId() + "/stock", "{\"stock\": 5}"))
                .andExpect(status().isNotFound());

            // assert
            assertThat(reload(product).getStock()).isEqualTo(10L);
            assertThat(reload(deleted).getStock()).isEqualTo(10L);
        }
    }

    @DisplayName("DELETE /api-admin/v1/products/{productId}")
    @Nested
    class Delete {

        @DisplayName("상품을 삭제하면 data가 null이고, 고객 상세 조회에서 404가 된다. (DEL-002)")
        @Test
        void deletesProduct_andHidesFromCustomer() throws Exception {
            // arrange
            Product product = saveProduct(brand.getId(), "가방");

            // act
            mvc.perform(adminDelete(PRODUCTS + "/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

            // assert
            assertThat(reload(product).getDeletedAt()).isNotNull();
            mvc.perform(get("/api/v1/products/" + product.getId())).andExpect(status().isNotFound());
        }

        @DisplayName("없거나 이미 삭제된 상품이면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void returnsNotFound_whenProductDoesNotExistOrIsDeleted() throws Exception {
            // arrange
            Product deleted = deletedProduct();

            // act & assert
            mvc.perform(adminDelete(PRODUCTS + "/999999")).andExpect(status().isNotFound());
            mvc.perform(adminDelete(PRODUCTS + "/" + deleted.getId())).andExpect(status().isNotFound());
        }
    }
}
