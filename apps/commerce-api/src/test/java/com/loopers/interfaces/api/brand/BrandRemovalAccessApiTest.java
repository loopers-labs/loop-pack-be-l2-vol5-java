package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.like.LikeService;
import com.loopers.application.order.OrderService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.support.AdminMockMvc;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BrandRemovalAccessApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private OrderService orders;
    @Autowired private LikeService likes;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private long brandId;
    private long productId;
    private long soldOutProductId;

    @BeforeEach
    void prepare() {
        initializer.initialize();
        Brand target = brands.save(new Brand("삭제 대상"));
        brandId = target.getId();
        productId = products.save(new Product(target, "기존 주문 상품", 1000, 5)).getId();
        soldOutProductId = products.save(new Product(target, "품절 상품", 2000, 0)).getId();
        Brand other = brands.save(new Brand("유지 브랜드"));
        products.save(new Product(other, "유지 상품", 3000, 10));
        jdbc.update("UPDATE user SET point_balance = 5000 WHERE id = 1");
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(productId, 1))).orderId();
        orders.confirm("alice", orderId);
        likes.register("alice", productId);
        likes.register("bob", productId);
    }

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @ParameterizedTest(name = "W3-BRAND-05: 일괄 삭제 후 {0} 거절")
    @MethodSource("mutationsAfterDeletion")
    void rejectsChangesToDeletedBrandAndProductsWithoutChangingRows(Mutation mutation) {
        deleteTargetBrand();
        var before = databaseState();

        var response = AdminMockMvc.exchange(mvc, mutation.method(), resolve(mutation.path()), "admin",
            resolve(mutation.body()));

        assertFailure(response, 404, mutation.errorCode(), mutation.message());
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test
    @DisplayName("W3-BRAND-05: 일반 사용자는 연결 상품이 있는 미삭제 브랜드를 삭제할 수 없다.")
    void rejectsNormalUserDeletionWithValidCsrfWithoutChangingRows() {
        var before = databaseState();

        var response = AdminMockMvc.exchange(mvc, HttpMethod.DELETE, brandPath(), "alice", null);

        assertFailure(response, 403, "FORBIDDEN", "관리자 권한이 필요합니다.");
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test
    @DisplayName("W3-BRAND-05: 미식별 요청은 연결 상품이 있는 미삭제 브랜드를 삭제할 수 없다.")
    void rejectsAnonymousDeletionWithValidCsrfWithoutChangingRows() {
        var before = databaseState();

        var response = AdminMockMvc.exchange(mvc, HttpMethod.DELETE, brandPath(), null, null);

        assertFailure(response, 403, "FORBIDDEN", "관리자 권한이 필요합니다.");
        assertThat(databaseState()).isEqualTo(before);
    }

    @ParameterizedTest(name = "W3-BRAND-05: 관리자 DELETE의 CSRF 불일치={0}, false이면 누락")
    @ValueSource(booleans = {false, true})
    void rejectsMissingOrInvalidCsrfWithoutChangingRows(boolean invalidToken) throws Exception {
        var before = databaseState();
        var request = delete(brandPath()).with(user("admin").roles("ADMIN"));
        if (invalidToken) {
            request.with(csrf().useInvalidToken());
        }

        mvc.perform(request)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.meta.result").value("FAIL"))
            .andExpect(jsonPath("$.meta.errorCode").value("FORBIDDEN"))
            .andExpect(jsonPath("$.meta.message").value("관리자 권한이 필요합니다."))
            .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(databaseState()).isEqualTo(before);
    }

    @Test
    @DisplayName("W3-BRAND-05: 일괄 삭제한 브랜드와 상품은 관리자 목록·상세에서 삭제 시각과 함께 조회된다.")
    void administratorCanReadDeletedRowsWithoutChangingThem() {
        deleteTargetBrand();
        var before = databaseState();

        JsonNode brand = successfulGet(brandPath());
        assertThat(brand.path("brandId").asLong()).isEqualTo(brandId);
        assertThat(brand.path("name").asText()).isEqualTo("삭제 대상");
        assertThat(brand.path("deletedAt").asText()).isNotBlank();
        assertThat(databaseState()).isEqualTo(before);

        JsonNode brandPage = successfulGet("/api-admin/v1/brands");
        assertThat(brandPage.path("totalElements").asLong()).isEqualTo(2);
        assertThat(brandPage.path("items")).anySatisfy(item -> {
            assertThat(item.path("brandId").asLong()).isEqualTo(brandId);
            assertThat(item.path("deletedAt")).isEqualTo(brand.path("deletedAt"));
        });
        assertThat(databaseState()).isEqualTo(before);

        for (long id : List.of(productId, soldOutProductId)) {
            JsonNode product = successfulGet("/api-admin/v1/products/" + id);
            assertThat(product.path("productId").asLong()).isEqualTo(id);
            assertThat(product.path("brand").path("brandId").asLong()).isEqualTo(brandId);
            assertThat(product.path("deletedAt")).isEqualTo(brand.path("deletedAt"));
            assertThat(databaseState()).isEqualTo(before);
        }

        JsonNode productPage = successfulGet("/api-admin/v1/products?brandId=" + brandId);
        assertThat(productPage.path("totalElements").asLong()).isEqualTo(2);
        assertThat(productPage.path("items")).extracting(item -> item.path("productId").asLong())
            .containsExactlyInAnyOrder(productId, soldOutProductId);
        assertThat(productPage.path("items")).allSatisfy(item ->
            assertThat(item.path("deletedAt")).isEqualTo(brand.path("deletedAt")));
        assertThat(databaseState()).isEqualTo(before);
    }

    private void deleteTargetBrand() {
        var response = AdminMockMvc.exchange(mvc, HttpMethod.DELETE, brandPath(), "admin", null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("data").path("deleted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand WHERE id = ? AND deleted_at IS NOT NULL",
            Long.class, brandId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE brand_id = ? AND deleted_at IS NOT NULL",
            Long.class, brandId)).isEqualTo(2L);
    }

    private JsonNode successfulGet(String path) {
        var response = AdminMockMvc.exchange(mvc, HttpMethod.GET, path, "admin", null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("meta").has("errorCode")).isFalse();
        return response.getBody().path("data");
    }

    private void assertFailure(ResponseEntity<JsonNode> response, int statusCode, String errorCode, String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(statusCode);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(errorCode);
        assertThat(response.getBody().path("meta").path("message").asText()).isEqualTo(message);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private String brandPath() {
        return "/api-admin/v1/brands/" + brandId;
    }

    private String resolve(String input) {
        return input.replace("{brand}", String.valueOf(brandId)).replace("{product}", String.valueOf(productId));
    }

    private Map<String, List<Map<String, Object>>> databaseState() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("user", "brand", "product", "like", "order", "order_item")) {
            result.put(table, jdbc.queryForList("SELECT * FROM `" + table + "` ORDER BY id"));
        }
        return result;
    }

    private static Stream<Mutation> mutationsAfterDeletion() {
        return Stream.of(
            new Mutation(HttpMethod.PUT, "/api-admin/v1/brands/{brand}", "{\"name\":\"수정 시도\"}",
                "BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다."),
            new Mutation(HttpMethod.PUT, "/api-admin/v1/products/{product}", "{\"name\":\"수정 시도\",\"price\":2000}",
                "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."),
            new Mutation(HttpMethod.PUT, "/api-admin/v1/products/{product}/stock", "{\"stockQuantity\":20}",
                "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."),
            new Mutation(HttpMethod.POST, "/api-admin/v1/products",
                "{\"brandId\":{brand},\"name\":\"등록 시도\",\"price\":3000,\"stockQuantity\":10}",
                "BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다.")
        );
    }

    private record Mutation(HttpMethod method, String path, String body, String errorCode, String message) {
    }
}
