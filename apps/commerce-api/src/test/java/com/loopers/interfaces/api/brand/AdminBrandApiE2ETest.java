package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.support.AdminMockMvc;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class AdminBrandApiE2ETest {
    @Autowired private MockMvc mvc;
    @Autowired private BrandRepository brands;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @Test
    void createsBrandAndReadsDeletedBrandAsAdmin() {
        var created = request(HttpMethod.POST, "/api-admin/v1/brands", "admin", "{\"name\":\"  브랜드  \"}");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode data = created.getBody().path("data");
        assertThat(data.path("name").asText()).isEqualTo("브랜드");
        assertThat(data.has("createdAt")).isTrue();
        assertThat(data.has("deletedAt")).isFalse();
        long id = data.path("brandId").asLong();
        jdbc.update("UPDATE brand SET deleted_at = '2026-09-18 00:00:00' WHERE id = ?", id);
        var detail = request(HttpMethod.GET, "/api-admin/v1/brands/" + id, "admin", null);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getBody().path("data").path("deletedAt").isTextual()).isTrue();
    }

    @Test
    void checksAdminBeforeParsingMalformedPathOrBody() {
        brands.save(new Brand("기존"));
        var before = jdbc.queryForList("SELECT * FROM brand");
        var path = request(HttpMethod.GET, "/api-admin/v1/brands/abc", "alice", null);
        var body = request(HttpMethod.POST, "/api-admin/v1/brands", "alice", "{bad");
        assertThat(path.getStatusCode().value()).isEqualTo(403);
        assertThat(body.getStatusCode().value()).isEqualTo(403);
        assertThat(path.getBody().path("meta").path("errorCode").asText()).isEqualTo("FORBIDDEN");
        assertThat(jdbc.queryForList("SELECT * FROM brand")).isEqualTo(before);
    }

    @Test
    void rejectsUnknownBodyFieldWithoutWriting() {
        var result = request(HttpMethod.POST, "/api-admin/v1/brands", "admin", "{\"name\":\"브랜드\",\"price\":1}");
        assertThat(result.getStatusCode().value()).isEqualTo(400);
        assertThat(result.getBody().path("meta").path("errorCode").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand", Long.class)).isZero();
    }

    @Test
    void mapsFrameworkErrorsAndRequesterErrors() {
        assertError(request(HttpMethod.PATCH, "/api-admin/v1/brands", "admin", null), 405, "METHOD_NOT_ALLOWED");
        assertError(request(HttpMethod.GET, "/api-admin/v1/brands/1", "unknown", null), 403, "FORBIDDEN");
        assertError(request(HttpMethod.GET, "/api-admin/v1/brands/1", "", null), 403, "FORBIDDEN");
        assertError(request(HttpMethod.GET, "/api-admin/v1/brands/1", null, null), 403, "FORBIDDEN");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        var unsupported = AdminMockMvc.exchange(mvc, HttpMethod.POST, "/api-admin/v1/brands",
            "admin", "name=brand", headers);
        assertError(unsupported, 415, "UNSUPPORTED_MEDIA_TYPE");
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_XML));
        var unacceptable = AdminMockMvc.exchange(mvc, HttpMethod.GET, "/api-admin/v1/brands/1",
            "admin", null, headers);
        assertError(unacceptable, 406, "NOT_ACCEPTABLE");
    }

    private void assertError(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String user, String body) {
        return AdminMockMvc.exchange(mvc, method, path, user, body);
    }
}
