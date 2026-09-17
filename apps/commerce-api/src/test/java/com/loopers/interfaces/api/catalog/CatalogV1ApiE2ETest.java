package com.loopers.interfaces.api.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.catalog.BrandModel;
import com.loopers.domain.catalog.ProductModel;
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

/**
 * 카탈로그 EP-01~06, EP-14~24 의 HTTP 계약 (설계 4-3, 4-4). 테스트 이름에 ER-ID.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogV1ApiE2ETest {

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
    @DisplayName("공통: 요청자 식별 (ER-01, ER-02)")
    class Requester {
        @DisplayName("[ER-01 USER_NOT_FOUND] 고객 API 에서 헤더 누락은 404.")
        @Test
        void customer_missingHeader() {
            api.get("/api/v1/products", null).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        @DisplayName("[ER-01 USER_NOT_FOUND] 고객 API 에서 헤더 형식 오류는 404 (DR-01).")
        @Test
        void customer_malformedHeader() {
            api.get("/api/v1/products", "abc").assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        @DisplayName("[ER-01 USER_NOT_FOUND] 고객 API 에서 없는 사용자는 404.")
        @Test
        void customer_unknownUser() {
            api.get("/api/v1/products", 999_999L).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        @DisplayName("[ER-01 USER_NOT_FOUND] 관리자 API 에서 헤더 누락·없는 사용자는 404 봉투로 나간다.")
        @Test
        void admin_userNotFound() {
            api.get("/api-admin/v1/brands", null).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
            api.get("/api-admin/v1/brands", 999_999L).assertError(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }

        @DisplayName("[ER-02 NOT_ADMIN] 관리자 API 에서 관리자가 아닌 사용자는 403 봉투.")
        @Test
        void admin_notAdmin() {
            api.get("/api-admin/v1/brands", user.getId()).assertError(HttpStatus.FORBIDDEN, "NOT_ADMIN");
            api.post("/api-admin/v1/brands", user.getId(), Map.of("name", "x")).assertError(HttpStatus.FORBIDDEN, "NOT_ADMIN");
        }

        @DisplayName("관리자 API 는 CSRF 토큰 없이 POST/PUT/DELETE 가 통과한다.")
        @Test
        void admin_writesWithoutCsrf() {
            api.post("/api-admin/v1/brands", admin.getId(), Map.of("name", "브랜드")).assertSuccess(HttpStatus.CREATED);
        }
    }

    @Nested
    @DisplayName("EP-01 GET /api/v1/brands/{brandId}")
    class GetBrand {
        @DisplayName("200 + BrandSummary(id, name). 삭제 여부 필드는 없다.")
        @Test
        void success() {
            BrandModel brand = fixtures.brand("나이키");

            var result = api.get("/api/v1/brands/" + brand.getId(), user.getId()).assertSuccess(HttpStatus.OK);

            assertThat(result.data().path("id").asLong()).isEqualTo(brand.getId());
            assertThat(result.data().path("name").asText()).isEqualTo("나이키");
            assertThat(result.data().has("deleted")).isFalse();
        }

        @DisplayName("[ER-03 BRAND_NOT_FOUND] 없음·삭제됨·경로 ID 형식 오류(DR-01) 모두 404.")
        @Test
        void brandNotFound() {
            BrandModel deleted = fixtures.deletedBrand("삭제됨");

            api.get("/api/v1/brands/999999", user.getId()).assertError(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND");
            api.get("/api/v1/brands/" + deleted.getId(), user.getId()).assertError(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND");
            api.get("/api/v1/brands/abc", user.getId()).assertError(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND");
        }
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
    @DisplayName("EP-04/05/06 좋아요")
    class Likes {
        @DisplayName("EP-04 등록·EP-05 취소는 200 + data null. EP-06 목록에 반영된다.")
        @Test
        void likeUnlikeList() {
            BrandModel brand = fixtures.brand("브랜드");
            ProductModel product = fixtures.product(brand.getId(), "상품", 1000L, 1);
            String likesPath = "/api/v1/products/" + product.getId() + "/likes";
            String myLikesPath = "/api/v1/users/" + user.getId() + "/likes";

            var liked = api.post(likesPath, user.getId(), null).assertSuccess(HttpStatus.OK);
            assertThat(liked.data().isNull() || liked.data().isMissingNode()).isTrue();

            var list = api.get(myLikesPath, user.getId()).assertSuccess(HttpStatus.OK);
            assertThat(list.data().path("totalCount").asLong()).isEqualTo(1);
            var item = list.data().path("items").get(0);
            assertThat(item.path("likedAt").asText()).isNotBlank();
            assertThat(item.path("product").path("id").asLong()).isEqualTo(product.getId());
            assertThat(item.path("product").path("brand").path("id").asLong()).isEqualTo(brand.getId());

            api.delete(likesPath, user.getId()).assertSuccess(HttpStatus.OK);
            assertThat(api.get(myLikesPath, user.getId()).data().path("totalCount").asLong()).isZero();
        }

        @DisplayName("[ER-04 PRODUCT_NOT_FOUND] 없는 상품에 등록·취소는 404.")
        @Test
        void productNotFound() {
            api.post("/api/v1/products/999999/likes", user.getId(), null).assertError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
            api.delete("/api/v1/products/999999/likes", user.getId()).assertError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        }

        @DisplayName("[ER-06 NOT_OWNER] 다른 사용자의 좋아요 목록은 403.")
        @Test
        void notOwner() {
            UserModel other = fixtures.user();

            api.get("/api/v1/users/" + other.getId() + "/likes", user.getId()).assertError(HttpStatus.FORBIDDEN, "NOT_OWNER");
        }

        @DisplayName("[ER-08 INVALID_PAGE] 내 좋아요 목록 페이지 오류는 400.")
        @Test
        void invalidPage() {
            api.get("/api/v1/users/" + user.getId() + "/likes?size=101", user.getId()).assertError(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
    }

    @Nested
    @DisplayName("EP-14~18 브랜드 (관리자)")
    class BrandAdmin {
        @DisplayName("EP-15 201 + BrandAdmin(deleted=false) → EP-16/17/18 → EP-14 목록에 삭제됨으로 남는다.")
        @Test
        void crudFlow() {
            var created = api.post("/api-admin/v1/brands", admin.getId(), Map.of("name", "브랜드")).assertSuccess(HttpStatus.CREATED);
            long brandId = created.data().path("id").asLong();
            assertThat(created.data().path("deleted").asBoolean()).isFalse();

            var updated = api.put("/api-admin/v1/brands/" + brandId, admin.getId(), Map.of("name", "새 이름")).assertSuccess(HttpStatus.OK);
            assertThat(updated.data().path("name").asText()).isEqualTo("새 이름");

            api.delete("/api-admin/v1/brands/" + brandId, admin.getId()).assertSuccess(HttpStatus.OK);

            var detail = api.get("/api-admin/v1/brands/" + brandId, admin.getId()).assertSuccess(HttpStatus.OK);
            assertThat(detail.data().path("deleted").asBoolean()).isTrue();

            var list = api.get("/api-admin/v1/brands?page=0&size=10", admin.getId()).assertSuccess(HttpStatus.OK);
            assertThat(list.data().path("totalCount").asLong()).isEqualTo(1);
            assertThat(list.data().path("items").get(0).path("deleted").asBoolean()).isTrue();
        }

        @DisplayName("[ER-17 INVALID_BRAND] 이름 누락·빈 값·101자는 400.")
        @Test
        void invalidBrand() {
            api.post("/api-admin/v1/brands", admin.getId(), Map.of()).assertError(HttpStatus.BAD_REQUEST, "INVALID_BRAND");
            api.post("/api-admin/v1/brands", admin.getId(), Map.of("name", "")).assertError(HttpStatus.BAD_REQUEST, "INVALID_BRAND");
            api.post("/api-admin/v1/brands", admin.getId(), Map.of("name", "a".repeat(101))).assertError(HttpStatus.BAD_REQUEST, "INVALID_BRAND");
        }

        @DisplayName("[ER-18 BRAND_HAS_PRODUCTS] 삭제되지 않은 상품이 연결된 브랜드 삭제는 409.")
        @Test
        void brandHasProducts() {
            BrandModel brand = fixtures.brand("브랜드");
            fixtures.product(brand.getId(), "상품", 1000L, 0);

            api.delete("/api-admin/v1/brands/" + brand.getId(), admin.getId()).assertError(HttpStatus.CONFLICT, "BRAND_HAS_PRODUCTS");
        }

        @DisplayName("[ER-03 BRAND_NOT_FOUND] 삭제된 브랜드의 수정·재삭제는 404.")
        @Test
        void brandNotFound_deleted() {
            BrandModel deleted = fixtures.deletedBrand("삭제됨");

            api.put("/api-admin/v1/brands/" + deleted.getId(), admin.getId(), Map.of("name", "x")).assertError(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND");
            api.delete("/api-admin/v1/brands/" + deleted.getId(), admin.getId()).assertError(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND");
        }

        @DisplayName("[ER-08 INVALID_PAGE] 관리자 브랜드 목록 페이지 오류는 400.")
        @Test
        void invalidPage() {
            api.get("/api-admin/v1/brands?page=-1", admin.getId()).assertError(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }

        @DisplayName("[ER-22 BAD_REQUEST] JSON 파싱 불가는 400 BAD_REQUEST.")
        @Test
        void malformedJson() {
            api.post("/api-admin/v1/brands", admin.getId(), "{not json").assertError(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
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
