package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.support.AdminMockMvc;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ProductApiE2ETest {
    @Autowired private TestRestTemplate http;
    @Autowired private MockMvc mvc;
    @Autowired private BrandRepository brands;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @Test
    void createsUpdatesChangesAbsoluteStockAndDeletesProduct() {
        long brandId = brands.save(new Brand("브랜드")).getId();
        var created = call(HttpMethod.POST, "/api-admin/v1/products", "admin",
            "{\"brandId\":" + brandId + ",\"name\":\"  상품  \",\"price\":1000,\"stockQuantity\":10}");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode initial = created.getBody().path("data");
        assertThat(initial.path("name").asText()).isEqualTo("상품");
        assertThat(initial.path("brand").path("brandId").asLong()).isEqualTo(brandId);
        assertThat(initial.path("likeCount").asLong()).isZero();
        assertThat(initial.has("createdAt")).isTrue();
        long id = initial.path("productId").asLong();
        String adminPath = "/api-admin/v1/products/" + id;
        var updated = call(HttpMethod.PUT, adminPath, "admin", "{\"name\":\"변경\",\"price\":2000}");
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody().path("data").path("stockQuantity").asInt()).isEqualTo(10);
        assertThat(updated.getBody().path("data").path("brand").path("brandId").asLong()).isEqualTo(brandId);
        var stock = call(HttpMethod.PUT, adminPath + "/stock", "admin", "{\"stockQuantity\":3}");
        assertThat(stock.getStatusCode().value()).isEqualTo(200);
        assertThat(stock.getBody().path("data").size()).isEqualTo(2);
        assertThat(stock.getBody().path("data").path("stockQuantity").asInt()).isEqualTo(3);
        var publicDetail = call(HttpMethod.GET, "/api/v1/products/" + id, "unknown", null);
        assertThat(publicDetail.getStatusCode().value()).isEqualTo(200);
        assertThat(publicDetail.getBody().path("data").path("name").asText()).isEqualTo("변경");
        assertThat(publicDetail.getBody().path("data").has("createdAt")).isFalse();
        assertThat(call(HttpMethod.DELETE, adminPath, "admin", null).getStatusCode().value()).isEqualTo(200);
        var deletedState = jdbc.queryForList("SELECT * FROM product");
        assertThat(call(HttpMethod.DELETE, adminPath, "admin", null).getStatusCode().value()).isEqualTo(200);
        assertError(call(HttpMethod.PUT, adminPath, "admin", "{\"name\":\"변경2\",\"price\":1}"), 404, "PRODUCT_NOT_FOUND");
        assertError(call(HttpMethod.PUT, adminPath + "/stock", "admin", "{\"stockQuantity\":0}"), 404, "PRODUCT_NOT_FOUND");
        assertError(call(HttpMethod.GET, "/api/v1/products/" + id, null, null), 404, "PRODUCT_NOT_FOUND");
        assertThat(call(HttpMethod.GET, adminPath, "admin", null).getBody().path("data").has("deletedAt")).isTrue();
        assertThat(jdbc.queryForList("SELECT * FROM product")).isEqualTo(deletedState);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null,\"price\":1}", "{\"name\":1,\"price\":1}",
        "{\"name\":\"ok\",\"price\":\"1\"}", "{\"name\":\"ok\",\"price\":1.0}",
        "{\"name\":\"ok\",\"price\":1,\"brandId\":1}", "{\"name\":\"ok\",\"price\":1,\"stockQuantity\":1}"})
    void rejectsInvalidUpdateBodiesWithoutChanges(String body) {
        var before = jdbc.queryForList("SELECT * FROM product");
        assertError(call(HttpMethod.PUT, "/api-admin/v1/products/1", "admin", body), 400, "INVALID_REQUEST");
        assertThat(jdbc.queryForList("SELECT * FROM product")).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sort=unknown", "page=-1", "page=abc", "size=0", "size=101", "size=1.5",
        "brandId=0", "brandId=abc", "page=2147483648"})
    void rejectsInvalidListInputs(String query) {
        assertError(call(HttpMethod.GET, "/api/v1/products?" + query, null, null), 400, "INVALID_REQUEST");
        assertError(call(HttpMethod.GET, "/api-admin/v1/products?" + query, "admin", null), 400, "INVALID_REQUEST");
    }

    @Test
    void rejectsUnavailableBrandAndInvalidStockWithoutChanges() {
        assertError(call(HttpMethod.POST, "/api-admin/v1/products", "admin",
            "{\"brandId\":1,\"name\":\"상품\",\"price\":1,\"stockQuantity\":0}"), 404, "BRAND_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isZero();
        assertError(call(HttpMethod.GET, "/api-admin/v1/products/abc", "alice", null), 403, "FORBIDDEN");
        assertError(call(HttpMethod.POST, "/api-admin/v1/products", "alice", "{bad"), 403, "FORBIDDEN");
        assertError(call(HttpMethod.GET, "/api/v1/products/0", null, null), 400, "INVALID_REQUEST");
        assertError(call(HttpMethod.GET, "/api-admin/v1/products/1", "admin", null), 404, "PRODUCT_NOT_FOUND");
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String requester, String body) {
        if (path.startsWith("/api-admin/")) {
            return AdminMockMvc.exchange(mvc, method, path, requester, body);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requester != null) {
            headers.set("X-USER-ID", requester);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void assertError(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().has("data")).isFalse();
    }
}
