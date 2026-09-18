package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.support.AdminMockMvc;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ProductQueryApiE2ETest {
    @Autowired private TestRestTemplate http;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private FixtureUserInitializer users;

    @BeforeEach
    void fixture() {
        users.initialize();
        jdbc.update("INSERT INTO brand (id,name,created_at,updated_at) VALUES "
            + "(10,'브랜드10','2026-09-18','2026-09-18'),(20,'브랜드20','2026-09-18','2026-09-18')");
        jdbc.update("INSERT INTO product (id,brand_id,name,price,stock_quantity,created_at,updated_at,deleted_at) VALUES "
            + "(101,10,'상품101',1000,5,'2026-09-18 01:00:00','2026-09-18',null),"
            + "(102,10,'상품102',1000,3,'2026-09-18 02:00:00','2026-09-18',null),"
            + "(103,20,'상품103',2000,4,'2026-09-18 02:00:00','2026-09-18',null),"
            + "(104,10,'상품104',500,7,'2026-09-18 00:00:00','2026-09-18',null),"
            + "(105,10,'상품105',500,1,'2026-09-18 03:00:00','2026-09-18','2026-09-18 04:00:00')");
        jdbc.update("INSERT INTO `like` (user_id,product_id,created_at) VALUES "
            + "(2,101,'2026-09-18'),(3,101,'2026-09-18'),(2,103,'2026-09-18'),(3,103,'2026-09-18'),"
            + "(2,104,'2026-09-18'),(3,104,'2026-09-18'),(2,105,'2026-09-18')");
    }

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @Test
    void sortsAllMatchingProductsBeforePagingAndIncludesZeroLikes() {
        var before = jdbc.queryForList("SELECT * FROM product ORDER BY id");
        assertIds(get("/api/v1/products?sort=latest", null), 103, 102, 101, 104);
        assertIds(get("/api/v1/products?sort=price_asc", "unknown"), 104, 102, 101, 103);
        JsonNode liked = get("/api/v1/products?sort=likes_desc", null);
        assertIds(liked, 104, 103, 101, 102);
        assertThat(liked.path("items").get(3).path("likeCount").asLong()).isZero();
        assertIds(get("/api-admin/v1/products?sort=latest", "admin"), 105, 103, 102, 101, 104);
        assertIds(get("/api/v1/products?sort=likes_desc&size=2&page=1", null), 101, 102);
        assertThat(jdbc.queryForList("SELECT * FROM product ORDER BY id")).isEqualTo(before);
    }

    @Test
    void filtersBeforeSortingAndReturnsStableTotalsForOutOfRangePage() {
        for (int page = 0; page <= 2; page++) {
            JsonNode data = get("/api/v1/products?brandId=10&sort=price_asc&size=2&page=" + page, null);
            assertThat(data.path("totalElements").asLong()).isEqualTo(3);
            assertThat(data.path("totalPages").asInt()).isEqualTo(2);
            if (page == 0) {
                assertIds(data, 104, 102);
            } else if (page == 1) {
                assertIds(data, 101);
            } else {
                assertIds(data);
            }
        }
        JsonNode huge = get("/api/v1/products?page=2147483647&size=100", null);
        assertIds(huge);
        assertThat(huge.path("totalElements").asLong()).isEqualTo(4);
        assertIds(get("/api/v1/products?brandId=999", null));
        jdbc.update("UPDATE brand SET deleted_at = CURRENT_TIMESTAMP WHERE id = 10");
        assertIds(get("/api/v1/products?brandId=10", null));
        var hidden = http.getForEntity("/api/v1/products/101", JsonNode.class);
        assertThat(hidden.getStatusCode().value()).isEqualTo(404);
        assertThat(hidden.getBody().path("meta").path("errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void detailContainsBrandAndActualRelationCountWithoutAdministrativeFields() {
        JsonNode data = get("/api/v1/products/101", null);
        assertThat(data.size()).isEqualTo(6);
        assertThat(data.path("productId").asLong()).isEqualTo(101);
        assertThat(data.path("brand").path("name").asText()).isEqualTo("브랜드10");
        assertThat(data.path("likeCount").asLong()).isEqualTo(2);
        assertThat(data.has("deletedAt")).isFalse();
    }

    private JsonNode get(String path, String user) {
        if (path.startsWith("/api-admin/")) {
            var response = AdminMockMvc.exchange(mvc, HttpMethod.GET, path, user, null);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            return response.getBody().path("data");
        }
        HttpHeaders headers = new HttpHeaders();
        if (user != null) {
            headers.set("X-USER-ID", user);
        }
        var response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().path("data");
    }

    private void assertIds(JsonNode data, long... ids) {
        List<Long> actual = new ArrayList<>();
        data.path("items").forEach(item -> actual.add(item.path("productId").asLong()));
        assertThat(actual).containsExactly(java.util.Arrays.stream(ids).boxed().toArray(Long[]::new));
    }
}
