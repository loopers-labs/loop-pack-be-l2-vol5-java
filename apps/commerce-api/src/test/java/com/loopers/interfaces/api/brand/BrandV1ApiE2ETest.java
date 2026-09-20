package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
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

/** 브랜드 EP-01, EP-14~18 의 HTTP 계약 (설계 4-3, 4-4). 테스트 이름에 ER-ID. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandV1ApiE2ETest {

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
}
