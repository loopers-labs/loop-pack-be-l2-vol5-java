package com.loopers.interfaces.api.product;

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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ProductBoundaryApiE2ETest {

    @Autowired private TestRestTemplate http;
    @Autowired private MockMvc mvc;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"stockQuantity\":-1}", "{\"stockQuantity\":2147483648}",
        "{\"stockQuantity\":-2147483649}", "{\"stockQuantity\":\"3\"}", "{\"stockQuantity\":1.0}",
        "{\"stockQuantity\":null}", "{\"stockQuantity\":true}", "{}"})
    void rejectsInvalidStockInputsAndPreservesEveryColumnOfAnExistingProduct(String body) {
        Product product = storedProduct();
        var before = jdbc.queryForList("SELECT * FROM product ORDER BY id");

        assertError(call(HttpMethod.PUT, adminPath(product) + "/stock", body), 400, "INVALID_REQUEST");

        assertThat(jdbc.queryForList("SELECT * FROM product ORDER BY id")).isEqualTo(before);
        assertThat(publicDetail(product.getId()).path("stockQuantity").asInt()).isEqualTo(10);
    }

    @Test
    void stockSupportsZeroAndIntegerMaximumAndCustomerReadsTheSameFinalQuantity() {
        Product product = storedProduct();
        for (int quantity : new int[] {3, 0, Integer.MAX_VALUE}) {
            var response = call(HttpMethod.PUT, adminPath(product) + "/stock", "{\"stockQuantity\":" + quantity + "}");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().path("data").path("stockQuantity").asInt()).isEqualTo(quantity);
            assertThat(publicDetail(product.getId()).path("stockQuantity").asInt()).isEqualTo(quantity);
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Integer.class, product.getId()))
                .isEqualTo(quantity);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidUpdates")
    void rejectsInvalidNameAndPriceWithoutPartialMutationOfAnExistingProduct(String body) {
        Product product = storedProduct();
        var before = jdbc.queryForList("SELECT * FROM product ORDER BY id");

        assertError(call(HttpMethod.PUT, adminPath(product), body), 400, "INVALID_REQUEST");

        assertThat(jdbc.queryForList("SELECT * FROM product ORDER BY id")).isEqualTo(before);
        assertThat(publicDetail(product.getId()).path("name").asText()).isEqualTo("기존 상품");
    }

    @Test
    void acceptsOneHundredUnicodeCodePointsAndLongMaximumPriceThroughHttp() {
        Brand brand = brands.save(new Brand("브랜드"));
        String emojiName = "😀".repeat(100);
        var response = call(HttpMethod.POST, "/api-admin/v1/products", "{\"brandId\":" + brand.getId()
            + ",\"name\":\"  " + emojiName + "  \",\"price\":9223372036854775807,\"stockQuantity\":2147483647}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode created = response.getBody().path("data");
        long productId = created.path("productId").asLong();
        assertThat(created.path("name").asText()).isEqualTo(emojiName);
        assertThat(created.path("price").asLong()).isEqualTo(Long.MAX_VALUE);
        assertThat(publicDetail(productId).path("name").asText()).isEqualTo(emojiName);

        String koreanName = "한".repeat(100);
        var updated = call(HttpMethod.PUT, "/api-admin/v1/products/" + productId,
            "{\"name\":\" " + koreanName + " \",\"price\":1}");
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody().path("data").path("name").asText()).isEqualTo(koreanName);
        assertThat(updated.getBody().path("data").path("stockQuantity").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(updated.getBody().path("data").path("brand").path("brandId").asLong()).isEqualTo(brand.getId());
    }

    @Test
    void creatingUnderDeletedOrMissingBrandReturnsTheSameErrorWithoutSaving() {
        Brand deleted = brands.save(new Brand("삭제 브랜드"));
        jdbc.update("UPDATE brand SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", deleted.getId());
        var brandsBefore = jdbc.queryForList("SELECT * FROM brand ORDER BY id");
        var deletedResponse = call(HttpMethod.POST, "/api-admin/v1/products", createBody(deleted.getId()));
        var missingResponse = call(HttpMethod.POST, "/api-admin/v1/products", createBody(Long.MAX_VALUE));

        assertError(deletedResponse, 404, "BRAND_NOT_FOUND");
        assertError(missingResponse, 404, "BRAND_NOT_FOUND");
        assertThat(deletedResponse.getBody().path("meta")).isEqualTo(missingResponse.getBody().path("meta"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM brand ORDER BY id")).isEqualTo(brandsBefore);
    }

    @Test
    void missingDeletedProductAndDeletedBrandProduceIdenticalPublicErrorsWithoutChanges() {
        Product deleted = storedProduct();
        Product hiddenByBrand = products.save(new Product(deleted.getBrand(), "브랜드 삭제로 숨김", 1, 0));
        jdbc.update("UPDATE product SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", deleted.getId());
        jdbc.update("UPDATE brand SET deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", deleted.getBrand().getId());
        var before = jdbc.queryForList("SELECT * FROM product ORDER BY id");
        var missing = http.getForEntity("/api/v1/products/" + Long.MAX_VALUE, JsonNode.class);
        var deletedResponse = http.getForEntity("/api/v1/products/" + deleted.getId(), JsonNode.class);
        var hiddenResponse = http.getForEntity("/api/v1/products/" + hiddenByBrand.getId(), JsonNode.class);

        assertError(missing, 404, "PRODUCT_NOT_FOUND");
        assertError(deletedResponse, 404, "PRODUCT_NOT_FOUND");
        assertError(hiddenResponse, 404, "PRODUCT_NOT_FOUND");
        assertThat(deletedResponse.getBody().path("meta")).isEqualTo(missing.getBody().path("meta"));
        assertThat(hiddenResponse.getBody().path("meta")).isEqualTo(missing.getBody().path("meta"));
        assertThat(jdbc.queryForList("SELECT * FROM product ORDER BY id")).isEqualTo(before);
    }

    private static Stream<String> invalidUpdates() {
        return Stream.of(
            "{\"name\":\" \",\"price\":1000}",
            "{\"name\":\"" + "😀".repeat(101) + "\",\"price\":1000}",
            "{\"name\":\"변경\",\"price\":0}",
            "{\"name\":\"변경\",\"price\":-1}",
            "{\"name\":\"변경\",\"price\":9223372036854775808}",
            "{\"name\":\"변경\",\"price\":-9223372036854775809}",
            "{\"name\":\"변경\",\"price\":\"1000\"}",
            "{\"name\":\"변경\",\"price\":1000.0}"
        );
    }

    private Product storedProduct() {
        return products.save(new Product(brands.save(new Brand("기존 브랜드")), "기존 상품", 1000, 10));
    }

    private String adminPath(Product product) {
        return "/api-admin/v1/products/" + product.getId();
    }

    private String createBody(long brandId) {
        return "{\"brandId\":" + brandId + ",\"name\":\"상품\",\"price\":1,\"stockQuantity\":0}";
    }

    private JsonNode publicDetail(long id) {
        var response = http.getForEntity("/api/v1/products/" + id, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().path("data");
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String body) {
        return AdminMockMvc.exchange(mvc, method, path, "admin", body);
    }

    private void assertError(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().has("data")).isFalse();
    }
}
