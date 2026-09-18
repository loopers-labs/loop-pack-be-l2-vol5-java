package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointOrderJourneyApiE2ETest {
    @Autowired private TestRestTemplate http;
    @Autowired private FixtureUserInitializer users;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private DatabaseCleanUp cleanup;

    @BeforeEach
    void fixture() {
        users.initialize();
    }

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @Test
    void chargesFromZeroConfirmsMultipleItemsAndReadsOwnOrderAndRemainingBalance() {
        Brand brand = brands.save(new Brand("여정 브랜드"));
        Product first = products.save(new Product(brand, "첫 상품", 2000, 5));
        Product second = products.save(new Product(brand, "둘째 상품", 3000, 4));

        assertThat(get("/api/v1/points", "alice").path("balance").asLong()).isZero();
        assertThat(post("/api/v1/points/charge", "{\"amount\":10000}", 200)
            .path("balance").asLong()).isEqualTo(10000);
        JsonNode draft = post("/api/v1/orders", "{\"items\":[{\"productId\":" + first.getId()
            + ",\"quantity\":2},{\"productId\":" + second.getId() + ",\"quantity\":1}]}", 201);

        long orderId = draft.path("orderId").asLong();
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("totalAmount").asLong()).isEqualTo(7000);
        assertThat(draft.path("items").size()).isEqualTo(2);
        assertItem(draft, first.getId(), "첫 상품", 2000, 2, 4000);
        assertItem(draft, second.getId(), "둘째 상품", 3000, 1, 3000);
        assertThat(get("/api/v1/points", "alice").path("balance").asLong()).isEqualTo(10000);
        assertThat(get("/api/v1/products/" + first.getId(), "alice").path("stockQuantity").asInt()).isEqualTo(5);
        assertThat(get("/api/v1/products/" + second.getId(), "alice").path("stockQuantity").asInt()).isEqualTo(4);

        JsonNode confirmed = post("/api/v1/orders/" + orderId + "/confirm", null, 200);
        assertThat(confirmed.path("orderId").asLong()).isEqualTo(orderId);
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("totalAmount").asLong()).isEqualTo(7000);
        assertThat(confirmed.path("paidAmount").asLong()).isEqualTo(7000);
        assertThat(confirmed.path("confirmedAt").asText()).isNotBlank();
        assertThat(confirmed.path("items")).isEqualTo(draft.path("items"));

        assertThat(get("/api/v1/orders/" + orderId, "alice")).isEqualTo(confirmed);
        JsonNode ownOrders = get("/api/v1/orders", "alice");
        assertThat(ownOrders.path("totalElements").asLong()).isEqualTo(1);
        assertThat(ownOrders.path("items").size()).isEqualTo(1);
        assertThat(ownOrders.path("items").get(0)).isEqualTo(confirmed);
        assertThat(get("/api/v1/points", "alice").path("balance").asLong()).isEqualTo(3000);
        assertThat(get("/api/v1/products/" + first.getId(), "alice").path("stockQuantity").asInt()).isEqualTo(3);
        assertThat(get("/api/v1/products/" + second.getId(), "alice").path("stockQuantity").asInt()).isEqualTo(3);
        assertThat(get("/api/v1/orders", "bob").path("totalElements").asLong()).isZero();
        assertThat(get("/api/v1/points", "bob").path("balance").asLong()).isZero();
    }

    private void assertItem(JsonNode order, long productId, String name, long price, int quantity, long subtotal) {
        JsonNode found = null;
        for (JsonNode item : order.path("items")) {
            if (item.path("productId").asLong() == productId) {
                found = item;
            }
        }
        assertThat(found).isNotNull();
        assertThat(found.path("productName").asText()).isEqualTo(name);
        assertThat(found.path("unitPrice").asLong()).isEqualTo(price);
        assertThat(found.path("quantity").asInt()).isEqualTo(quantity);
        assertThat(found.path("subtotal").asLong()).isEqualTo(subtotal);
    }

    private JsonNode get(String path, String requester) {
        return request(HttpMethod.GET, path, requester, null, 200);
    }

    private JsonNode post(String path, String body, int status) {
        return request(HttpMethod.POST, path, "alice", body, status);
    }

    private JsonNode request(HttpMethod method, String path, String requester, String body, int status) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", requester);
        ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        return response.getBody().path("data");
    }
}
