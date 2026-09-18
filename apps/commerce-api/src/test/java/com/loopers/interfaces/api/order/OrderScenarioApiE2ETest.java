package com.loopers.interfaces.api.order;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class OrderScenarioApiE2ETest {
    @Autowired private TestRestTemplate http;
    @Autowired private MockMvc mvc;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    @BeforeEach
    void fixture() {
        users.initialize();
        jdbc.update("UPDATE user SET point_balance=10000 WHERE id=1");
    }

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @Test
    void api09And12CreateTwoSnapshotsAndDeductAllProductsAndBalanceTogether() {
        Product first = product(1000, 5);
        Product second = product(2000, 4);
        String body = "{\"items\":[" + item(first.getId(), 2) + "," + item(second.getId(), 1) + "]}";
        JsonNode created = success(call(HttpMethod.POST, "/api/v1/orders", "alice", body), 201);
        assertThat(created.path("totalAmount").asLong()).isEqualTo(4000);
        assertThat(created.path("items").size()).isEqualTo(2);
        created.path("items").forEach(value -> assertThat(value.path("subtotal").asLong()).isEqualTo(2000));
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.has("paidAmount")).isFalse();
        assertThat(created.has("confirmedAt")).isFalse();
        assertStock(first.getId(), 5);
        assertStock(second.getId(), 4);
        assertBalance(10000);
        long id = created.path("orderId").asLong();
        JsonNode repeated = success(call(HttpMethod.POST, "/api/v1/orders", "alice", body), 201);
        assertThat(repeated.path("orderId").asLong()).isNotEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order`", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Long.class)).isEqualTo(4);
        JsonNode confirmed = success(call(HttpMethod.POST, "/api/v1/orders/" + id + "/confirm", "alice", null), 200);
        assertThat(confirmed.path("paidAmount").asLong()).isEqualTo(4000);
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertStock(first.getId(), 3);
        assertStock(second.getId(), 3);
        assertBalance(6000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deleted_product", "deleted_brand"})
    void api10UnavailableLaterItemDoesNotLeaveAnyPartialDraft(String scenario) {
        Product first = product(1000, 5);
        Product second = product(2000, 4);
        long secondId = second.getId();
        if (scenario.equals("missing")) {
            secondId = Long.MAX_VALUE;
        } else if (scenario.equals("deleted_product")) {
            jdbc.update("UPDATE product SET deleted_at=CURRENT_TIMESTAMP(6) WHERE id=?", secondId);
        } else {
            jdbc.update("UPDATE brand SET deleted_at=CURRENT_TIMESTAMP(6) WHERE id=?", second.getBrand().getId());
        }
        var before = snapshot();
        var response = call(HttpMethod.POST, "/api/v1/orders", "alice",
            "{\"items\":[" + item(first.getId(), 2) + "," + item(secondId, 1) + "]}");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void api11MergedQuantityFailsAtFourThenSucceedsAtFiveWithoutCreatingMoreItems() {
        Product product = product(1000, 4);
        JsonNode created = success(call(HttpMethod.POST, "/api/v1/orders", "alice",
            "{\"items\":[" + item(product.getId(), 2) + "," + item(product.getId(), 3) + "]}"), 201);
        assertThat(created.path("items").size()).isEqualTo(1);
        assertThat(created.path("items").get(0).path("quantity").asInt()).isEqualTo(5);
        assertThat(created.path("totalAmount").asLong()).isEqualTo(5000);
        long orderId = created.path("orderId").asLong();
        var before = snapshot();
        var failed = call(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", "alice", null);
        assertThat(failed.getStatusCode().value()).isEqualTo(409);
        assertThat(failed.getBody().path("meta").path("errorCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(snapshot()).isEqualTo(before);
        success(call(HttpMethod.PUT, "/api-admin/v1/products/" + product.getId() + "/stock", "admin", "{\"stockQuantity\":5}"), 200);
        success(call(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", "alice", null), 200);
        assertStock(product.getId(), 0);
        assertBalance(5000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE order_id=?", Long.class, orderId)).isEqualTo(1);
    }

    private Product product(long price, int stock) {
        return products.save(new Product(brands.save(new Brand("브랜드")), "상품", price, stock));
    }

    private String item(long id, int quantity) {
        return "{\"productId\":" + id + ",\"quantity\":" + quantity + "}";
    }

    private JsonNode success(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        return response.getBody().path("data");
    }

    private void assertStock(long id, int quantity) {
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, id)).isEqualTo(quantity);
    }

    private void assertBalance(long amount) {
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(amount);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"));
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String user, String body) {
        if (path.startsWith("/api-admin/")) {
            return AdminMockMvc.exchange(mvc, method, path, user, body);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", user);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
