package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.ApiTestClient;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 상품 EP-02~03, EP-19~24 의 HTTP 계약 (설계 4-3, 4-4). 테스트 이름에 ER-ID. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductV1ApiE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ApiTestClient api;
    private UserModel user;
    private UserModel admin;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(restTemplate, objectMapper);
        user = fixtures.user();
        admin = fixtures.admin();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("EP-02 GET /api/v1/products")
    class ListProducts {
        @DisplayName("200 + Page<ProductSummary>. 재고 없음 (DR-22), 페이지 정보는 data 안 (DR-19).")
        @Test
        void success() {
            BrandModel brand = fixtures.brand("브랜드");
            fixtures.product(brand.getId(), "상품", 1000L, 3);

            var result = api.get("/api/v1/products?sort=latest&page=0&size=5", user.getId()).assertSuccess(HttpStatus.OK);

            assertThat(result.data().path("totalCount").asLong()).isEqualTo(1);
            assertThat(result.data().path("page").asInt()).isZero();
            assertThat(result.data().path("size").asInt()).isEqualTo(5);
            var item = result.data().path("items").get(0);
            assertThat(item.path("name").asText()).isEqualTo("상품");
            assertThat(item.path("brand").path("name").asText()).isEqualTo("브랜드");
            assertThat(item.path("likeCount").asLong()).isZero();
            assertThat(item.has("stock")).isFalse();
        }

        @DisplayName("[ER-07 INVALID_SORT] 허용 목록 밖, 둘 이상 지정 모두 400.")
        @Test
        void invalidSort() {
            api.get("/api/v1/products?sort=name", user.getId()).assertError(HttpStatus.BAD_REQUEST, "INVALID_SORT");
            api.get("/api/v1/products?sort=latest&sort=price_asc", user.getId()).assertError(HttpStatus.BAD_REQUEST, "INVALID_SORT");
        }

        @DisplayName("[ER-08 INVALID_PAGE] 음수·0 크기·타입 오류 모두 400.")
        @Test
        void invalidPage() {
            api.get("/api/v1/products?page=-1", user.getId()).assertError(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
            api.get("/api/v1/products?size=0", user.getId()).assertError(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
            api.get("/api/v1/products?page=abc", user.getId()).assertError(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
    }

    @Nested
    @DisplayName("EP-03 GET /api/v1/products/{productId}")
    class GetProduct {
        @DisplayName("[ER-04 PRODUCT_NOT_FOUND] 없음·삭제됨·형식 오류 모두 404.")
        @Test
        void productNotFound() {
            BrandModel brand = fixtures.brand("브랜드");
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 1000L, 1);

            api.get("/api/v1/products/999999", user.getId()).assertError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
            api.get("/api/v1/products/" + deleted.getId(), user.getId()).assertError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
            api.get("/api/v1/products/x", user.getId()).assertError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("EP-19~24 상품 (관리자)")
    class ProductAdmin {
        @DisplayName("EP-20 201 + ProductAdmin → EP-22 수정 → EP-24 재고 → EP-23 삭제 → EP-21 삭제됨 표시.")
        @Test
        void crudFlow() {
            BrandModel brand = fixtures.brand("브랜드");

            var created = api.post("/api-admin/v1/products", admin.getId(),
                Map.of("brandId", brand.getId(), "name", "상품", "price", 1000, "stock", 5)).assertSuccess(HttpStatus.CREATED);
            long productId = created.data().path("id").asLong();
            assertThat(created.data().path("stock").asInt()).isEqualTo(5);
            assertThat(created.data().path("brand").path("id").asLong()).isEqualTo(brand.getId());
            assertThat(created.data().path("likeCount").asLong()).isZero();
            assertThat(created.data().path("deleted").asBoolean()).isFalse();

            var updated = api.put("/api-admin/v1/products/" + productId, admin.getId(),
                Map.of("name", "새 이름", "price", 2000)).assertSuccess(HttpStatus.OK);
            assertThat(updated.data().path("price").asLong()).isEqualTo(2000L);
            assertThat(updated.data().path("stock").asInt()).isEqualTo(5);

            var stock = api.put("/api-admin/v1/products/" + productId + "/stock", admin.getId(), Map.of("stock", 30)).assertSuccess(HttpStatus.OK);
            assertThat(stock.data().path("productId").asLong()).isEqualTo(productId);
            assertThat(stock.data().path("stock").asInt()).isEqualTo(30);

            api.delete("/api-admin/v1/products/" + productId, admin.getId()).assertSuccess(HttpStatus.OK);

            var detail = api.get("/api-admin/v1/products/" + productId, admin.getId()).assertSuccess(HttpStatus.OK);
            assertThat(detail.data().path("deleted").asBoolean()).isTrue();

            var list = api.get("/api-admin/v1/products", admin.getId()).assertSuccess(HttpStatus.OK);
            assertThat(list.data().path("totalCount").asLong()).isEqualTo(1);
        }

        @DisplayName("[ER-03 BRAND_NOT_FOUND] 없는 브랜드로 상품 생성은 404.")
        @Test
        void brandNotFound() {
            api.post("/api-admin/v1/products", admin.getId(),
                Map.of("brandId", 999999, "name", "상품", "price", 1000, "stock", 5)).assertError(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND");
        }

        @DisplayName("[ER-19 INVALID_PRODUCT_NAME] 이름 누락은 400.")
        @Test
        void invalidProductName() {
            BrandModel brand = fixtures.brand("브랜드");

            api.post("/api-admin/v1/products", admin.getId(),
                Map.of("brandId", brand.getId(), "price", 1000, "stock", 5)).assertError(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_NAME");
        }

        @DisplayName("[ER-20 INVALID_PRODUCT_PRICE] 가격 누락·타입 오류·음수는 400.")
        @Test
        void invalidProductPrice() {
            BrandModel brand = fixtures.brand("브랜드");

            api.post("/api-admin/v1/products", admin.getId(),
                Map.of("brandId", brand.getId(), "name", "상품", "stock", 5)).assertError(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_PRICE");
            api.post("/api-admin/v1/products", admin.getId(),
                Map.of("brandId", brand.getId(), "name", "상품", "price", "abc", "stock", 5)).assertError(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_PRICE");
            api.post("/api-admin/v1/products", admin.getId(),
                Map.of("brandId", brand.getId(), "name", "상품", "price", -1, "stock", 5)).assertError(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_PRICE");
        }

        @DisplayName("[ER-21 INVALID_STOCK] 재고 누락·타입 오류·음수는 400 (생성·재고 변경).")
        @Test
        void invalidStock() {
            BrandModel brand = fixtures.brand("브랜드");
            ProductModel product = fixtures.product(brand.getId(), "상품", 1000L, 1);

            api.post("/api-admin/v1/products", admin.getId(),
                Map.of("brandId", brand.getId(), "name", "상품", "price", 1000)).assertError(HttpStatus.BAD_REQUEST, "INVALID_STOCK");
            api.put("/api-admin/v1/products/" + product.getId() + "/stock", admin.getId(), Map.of("stock", "many")).assertError(HttpStatus.BAD_REQUEST, "INVALID_STOCK");
            api.put("/api-admin/v1/products/" + product.getId() + "/stock", admin.getId(), Map.of("stock", -1)).assertError(HttpStatus.BAD_REQUEST, "INVALID_STOCK");
        }

        @DisplayName("[ER-04 PRODUCT_NOT_FOUND] 삭제된 상품의 수정·재고 변경·재삭제는 404.")
        @Test
        void productNotFound_deleted() {
            BrandModel brand = fixtures.brand("브랜드");
            ProductModel deleted = fixtures.deletedProduct(brand.getId(), "삭제됨", 1000L, 1);
            String path = "/api-admin/v1/products/" + deleted.getId();

            api.put(path, admin.getId(), Map.of("name", "x", "price", 1)).assertError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
            api.put(path + "/stock", admin.getId(), Map.of("stock", 1)).assertError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
            api.delete(path, admin.getId()).assertError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        }

        @DisplayName("[ER-22 BAD_REQUEST] 바디 ID(brandId) 타입 오류는 BAD_REQUEST (4-4 메모: 바디 ID 는 ER-22).")
        @Test
        void bodyIdTypeError() {
            api.post("/api-admin/v1/products", admin.getId(),
                Map.of("brandId", "abc", "name", "상품", "price", 1000, "stock", 5)).assertError(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        }
    }
}
