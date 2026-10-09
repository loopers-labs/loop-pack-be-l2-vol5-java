package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class BrandRemovalCustomerApiTest {
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private TestRestTemplate rest;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    private Brand target;
    private Brand other;
    private Product first;
    private Product second;
    private Product remaining;

    @BeforeEach
    void setUp() {
        initializer.initialize();
        target = brands.save(new Brand("삭제 브랜드"));
        first = products.save(new Product(target, "삭제 상품", 100, 5));
        second = products.save(new Product(target, "품절 상품", 200, 0));
        other = brands.save(new Brand("유지 브랜드"));
        remaining = products.save(new Product(other, "유지 상품", 300, 7));
        jdbc.update("UPDATE user SET point_balance = 1000 WHERE id = 1");
    }

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "price_asc", "likes_desc"})
    @DisplayName("W3-BRAND-04: 실제 일괄 삭제 후 고객 상세·목록·브랜드 필터에서 제외하고 다른 상품은 노출한다.")
    void hidesDeletedBrandAndProductsForEverySort(String sort) {
        assertThat(success(request(HttpMethod.GET, "/api/v1/products?sort=" + sort, null, null), 200)
            .path("totalElements").asLong()).isEqualTo(3);
        deleteBrand();
        var beforeQueries = storedState();

        failure(request(HttpMethod.GET, "/api/v1/brands/" + target.getId(), null, null), "BRAND_NOT_FOUND");
        for (Product product : List.of(first, second)) {
            failure(request(HttpMethod.GET, "/api/v1/products/" + product.getId(), null, null), "PRODUCT_NOT_FOUND");
        }
        JsonNode page = success(request(HttpMethod.GET, "/api/v1/products?size=1&sort=" + sort, null, null), 200);
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        assertThat(page.path("totalPages").asInt()).isEqualTo(1);
        assertThat(page.path("items").findValuesAsText("productId")).containsExactly(remaining.getId().toString());
        assertThat(success(request(HttpMethod.GET, "/api/v1/products?page=1&size=1&sort=" + sort, null, null), 200)
            .path("items")).isEmpty();
        JsonNode filtered = success(request(HttpMethod.GET,
            "/api/v1/products?brandId=" + target.getId() + "&sort=" + sort, null, null), 200);
        assertThat(filtered.path("items")).isEmpty();
        assertThat(filtered.path("totalElements").asLong()).isZero();
        assertThat(success(request(HttpMethod.GET, "/api/v1/brands/" + other.getId(), null, null), 200)
            .path("brandId").asLong()).isEqualTo(other.getId());
        assertThat(success(request(HttpMethod.GET, "/api/v1/products/" + remaining.getId(), null, null), 200)
            .path("productId").asLong()).isEqualTo(remaining.getId());
        assertThat(storedState()).isEqualTo(beforeQueries);
    }

    @Test
    @DisplayName("W3-BRAND-04: 새 좋아요·주문은 거절하고 삭제 상품의 본인 좋아요 취소만 허용한다.")
    void rejectsNewUseButPreservesOtherUsersLikesWhenCancelling() {
        success(request(HttpMethod.POST, likePath(first), "alice", null), 200);
        success(request(HttpMethod.POST, likePath(first), "bob", null), 200);
        success(request(HttpMethod.POST, likePath(remaining), "alice", null), 200);
        var likesBeforeDeletion = jdbc.queryForList("SELECT * FROM `like` ORDER BY id");
        deleteBrand();
        assertThat(jdbc.queryForList("SELECT * FROM `like` ORDER BY id")).isEqualTo(likesBeforeDeletion);
        var beforeRequests = storedState();

        failure(request(HttpMethod.POST, likePath(second), "bob", null), "PRODUCT_NOT_FOUND");
        failure(request(HttpMethod.POST, likePath(first), "alice", null), "PRODUCT_NOT_FOUND");
        assertThat(success(request(HttpMethod.GET, "/api/v1/users/alice/likes", "alice", null), 200)
            .path("items").findValuesAsText("productId")).containsExactly(remaining.getId().toString());
        JsonNode bobLikes = success(request(HttpMethod.GET, "/api/v1/users/bob/likes", "bob", null), 200);
        assertThat(bobLikes.path("items")).isEmpty();
        assertThat(bobLikes.path("totalElements").asLong()).isZero();
        failure(request(HttpMethod.POST, "/api/v1/orders", "alice", orderBody()), "PRODUCT_NOT_FOUND");
        assertThat(storedState()).isEqualTo(beforeRequests);

        JsonNode cancelled = success(request(HttpMethod.DELETE, likePath(first), "alice", null), 200);
        assertThat(cancelled.path("productId").asLong()).isEqualTo(first.getId());
        assertThat(cancelled.path("liked").asBoolean()).isFalse();
        assertThat(cancelled.path("likeCount").asLong()).isEqualTo(1);
        var expected = new LinkedHashMap<>(beforeRequests);
        expected.put("likes", likesBeforeDeletion.stream().filter(row ->
            ((Number) row.get("user_id")).longValue() != 1
                || ((Number) row.get("product_id")).longValue() != first.getId()).toList());
        assertThat(storedState()).isEqualTo(expected);
        assertThat(success(request(HttpMethod.DELETE, likePath(first), "alice", null), 200)).isEqualTo(cancelled);
        assertThat(storedState()).isEqualTo(expected);
    }

    @Test
    @DisplayName("W3-BRAND-06: 삭제 전 DRAFT는 확정할 수 없고 주문·재고·잔액·스냅샷을 보존한다.")
    void rejectsDraftConfirmationAfterBrandDeletionWithoutAnyDeduction() {
        JsonNode draft = success(request(HttpMethod.POST, "/api/v1/orders", "alice", orderBody()), 201);
        long orderId = draft.path("orderId").asLong();
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("totalAmount").asLong()).isEqualTo(500);
        JsonNode beforeDeletion = success(request(HttpMethod.GET, "/api/v1/orders/" + orderId, "alice", null), 200);
        deleteBrand();
        var beforeConfirmation = storedState();

        failure(request(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", "alice", null), "PRODUCT_NOT_FOUND");

        assertThat(success(request(HttpMethod.GET, "/api/v1/orders/" + orderId, "alice", null), 200)).isEqualTo(beforeDeletion);
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, orderId)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForMap("SELECT paid_amount, confirmed_at FROM `order` WHERE id = ?", orderId))
            .containsEntry("paid_amount", null).containsEntry("confirmed_at", null);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Integer.class, first.getId())).isEqualTo(5);
        assertThat(success(request(HttpMethod.GET, "/api/v1/points", "alice", null), 200).path("balance").asLong()).isEqualTo(1000);
        assertThat(storedState()).isEqualTo(beforeConfirmation);
    }

    @Test
    @DisplayName("W3-BRAND-04·06 보존: 삭제 후에도 본인·관리자 주문 조회는 기존 확정 스냅샷을 반환한다.")
    void preservesConfirmedOrderThroughCustomerAndAdminQueries() {
        long orderId = success(request(HttpMethod.POST, "/api/v1/orders", "alice", orderBody()), 201)
            .path("orderId").asLong();
        JsonNode confirmed = success(request(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", "alice", null), 200);
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("paidAmount").asLong()).isEqualTo(500);
        JsonNode changedProduct = success(AdminMockMvc.exchange(mvc, HttpMethod.PUT,
            "/api-admin/v1/products/" + first.getId(), "admin", "{\"name\":\"변경된 상품\",\"price\":999}"), 200);
        assertThat(changedProduct.path("name").asText()).isEqualTo("변경된 상품");
        assertThat(changedProduct.path("price").asLong()).isEqualTo(999);
        assertThat(success(request(HttpMethod.GET, "/api/v1/orders/" + orderId, "alice", null), 200)).isEqualTo(confirmed);
        JsonNode customerPage = success(request(HttpMethod.GET, "/api/v1/orders", "alice", null), 200);
        JsonNode adminDetail = success(AdminMockMvc.exchange(mvc, HttpMethod.GET,
            "/api-admin/v1/orders/" + orderId, "admin", null), 200);
        JsonNode adminPage = success(AdminMockMvc.exchange(mvc, HttpMethod.GET,
            "/api-admin/v1/orders?userId=alice&status=CONFIRMED", "admin", null), 200);
        deleteBrand();
        var beforeQueries = storedState();

        assertThat(success(request(HttpMethod.GET, "/api/v1/orders/" + orderId, "alice", null), 200)).isEqualTo(confirmed);
        assertThat(success(request(HttpMethod.GET, "/api/v1/orders", "alice", null), 200)).isEqualTo(customerPage);
        assertThat(success(AdminMockMvc.exchange(mvc, HttpMethod.GET,
            "/api-admin/v1/orders/" + orderId, "admin", null), 200)).isEqualTo(adminDetail);
        assertThat(success(AdminMockMvc.exchange(mvc, HttpMethod.GET,
            "/api-admin/v1/orders?userId=alice&status=CONFIRMED", "admin", null), 200)).isEqualTo(adminPage);
        assertThat(adminDetail.path("userId").asText()).isEqualTo("alice");
        failure(request(HttpMethod.GET, "/api/v1/orders/" + orderId, "bob", null), "ORDER_NOT_FOUND");
        assertThat(success(request(HttpMethod.GET, "/api/v1/points", "alice", null), 200).path("balance").asLong()).isEqualTo(500);
        assertThat(storedState()).isEqualTo(beforeQueries);
    }

    private void deleteBrand() {
        JsonNode deleted = success(AdminMockMvc.exchange(mvc, HttpMethod.DELETE,
            "/api-admin/v1/brands/" + target.getId(), "admin", null), 200);
        assertThat(deleted.path("brandId").asLong()).isEqualTo(target.getId());
        assertThat(deleted.path("deleted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE brand_id = ? AND deleted_at IS NOT NULL",
            Long.class, target.getId())).isEqualTo(2);
    }

    private String likePath(Product product) {
        return "/api/v1/products/" + product.getId() + "/likes";
    }

    private String orderBody() {
        return "{\"items\":[{\"productId\":" + first.getId() + ",\"quantity\":2},{\"productId\":"
            + remaining.getId() + ",\"quantity\":1}]}";
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String requester, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requester != null) {
            headers.set("X-USER-ID", requester);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode success(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("meta").has("errorCode")).isFalse();
        assertThat(response.getBody().path("meta").has("message")).isFalse();
        assertThat(response.getBody().has("data")).isTrue();
        return response.getBody().path("data");
    }

    private void failure(ResponseEntity<JsonNode> response, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        String message = switch (code) {
            case "BRAND_NOT_FOUND" -> "브랜드를 찾을 수 없습니다.";
            case "ORDER_NOT_FOUND" -> "주문을 찾을 수 없습니다.";
            default -> "상품을 찾을 수 없습니다.";
        };
        assertThat(response.getBody().path("meta").path("message").asText()).isEqualTo(message);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of(
            "brands", jdbc.queryForList("SELECT * FROM brand ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "likes", jdbc.queryForList("SELECT * FROM `like` ORDER BY id")
        );
    }
}
