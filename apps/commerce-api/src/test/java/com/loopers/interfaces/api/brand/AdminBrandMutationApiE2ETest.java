package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AdminBrandMutationApiE2ETest {
    @Autowired private TestRestTemplate http;
    @Autowired private MockMvc mvc;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @Test
    void renamesAndSoftDeletesIdempotently() {
        long id = brands.save(new Brand("기존")).getId();
        String path = "/api-admin/v1/brands/" + id;
        var renamed = request(HttpMethod.PUT, path, "{\"name\":\"  변경  \"}");
        assertThat(renamed.getStatusCode().value()).isEqualTo(200);
        assertThat(renamed.getBody().path("data").path("name").asText()).isEqualTo("변경");
        assertThat(http.getForEntity("/api/v1/brands/" + id, JsonNode.class).getBody()
            .path("data").path("name").asText()).isEqualTo("변경");
        var deleted = request(HttpMethod.DELETE, path, null);
        assertThat(deleted.getStatusCode().value()).isEqualTo(200);
        assertThat(deleted.getBody().path("data").path("deleted").asBoolean()).isTrue();
        var before = jdbc.queryForList("SELECT * FROM brand");
        assertThat(request(HttpMethod.DELETE, path, null).getStatusCode().value()).isEqualTo(200);
        assertThat(request(HttpMethod.PUT, path, "{\"name\":\"복원 시도\"}").getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForList("SELECT * FROM brand")).isEqualTo(before);
    }

    @Test
    void rejectsDeletionEvenWhenActiveProductHasZeroStock() {
        Brand brand = brands.save(new Brand("브랜드"));
        Product product = products.save(new Product(brand, "품절", 100, 0));
        var before = jdbc.queryForList("SELECT * FROM brand");
        var result = request(HttpMethod.DELETE, "/api-admin/v1/brands/" + brand.getId(), null);
        assertThat(result.getStatusCode().value()).isEqualTo(409);
        assertThat(result.getBody().path("meta").path("errorCode").asText()).isEqualTo("BRAND_HAS_PRODUCTS");
        assertThat(jdbc.queryForList("SELECT * FROM brand")).isEqualTo(before);
        jdbc.update("UPDATE product SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", product.getId());
        assertThat(request(HttpMethod.DELETE, "/api-admin/v1/brands/" + brand.getId(), null).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void pagesAllBrandsIncludingDeletedWithIdTieBreak() {
        Brand first = brands.save(new Brand("첫째"));
        Brand second = brands.save(new Brand("둘째"));
        jdbc.update("UPDATE brand SET created_at = '2026-09-18 00:00:00'");
        jdbc.update("UPDATE brand SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", second.getId());
        var page = request(HttpMethod.GET, "/api-admin/v1/brands?page=0&size=1", null);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        JsonNode data = page.getBody().path("data");
        assertThat(data.path("items").get(0).path("brandId").asLong()).isEqualTo(second.getId());
        assertThat(data.path("totalElements").asLong()).isEqualTo(2);
        assertThat(data.path("totalPages").asInt()).isEqualTo(2);
        assertThat(request(HttpMethod.GET, "/api-admin/v1/brands?page=1&size=1", null)
            .getBody().path("data").path("items").get(0).path("brandId").asLong()).isEqualTo(first.getId());
        assertThat(request(HttpMethod.GET, "/api-admin/v1/brands?page=2&size=1", null)
            .getBody().path("data").path("items").isEmpty()).isTrue();
        assertThat(request(HttpMethod.GET, "/api-admin/v1/brands?size=101", null).getStatusCode().value()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=abc", "page=1.5", "page=2147483648", "size=0", "size=101", "size=1.5"})
    void rejectsInvalidPageAndPreservesRows(String query) {
        brands.save(new Brand("기존"));
        var before = jdbc.queryForList("SELECT * FROM brand");
        var response = request(HttpMethod.GET, "/api-admin/v1/brands?" + query, null);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(jdbc.queryForList("SELECT * FROM brand")).isEqualTo(before);
    }

    @Test
    void invalidNamesNeverChangeExistingBrandOrInsertRows() {
        long id = brands.save(new Brand("기존")).getId();
        var before = jdbc.queryForList("SELECT * FROM brand");
        for (String body : new String[] {"{\"name\":\"   \"}", "{\"name\":\"" + "😀".repeat(101) + "\"}",
            "{\"name\":null}", "{\"name\":1}", "{}"}) {
            assertThat(request(HttpMethod.PUT, "/api-admin/v1/brands/" + id, body).getStatusCode().value()).isEqualTo(400);
            assertThat(request(HttpMethod.POST, "/api-admin/v1/brands", body).getStatusCode().value()).isEqualTo(400);
            assertThat(jdbc.queryForList("SELECT * FROM brand")).isEqualTo(before);
        }
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String body) {
        return AdminMockMvc.exchange(mvc, method, path, "admin", body);
    }
}
