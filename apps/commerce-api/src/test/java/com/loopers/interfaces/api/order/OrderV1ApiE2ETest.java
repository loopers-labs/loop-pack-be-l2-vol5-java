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
class OrderV1ApiE2ETest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private BrandRepository brands;
    @Autowired
    private ProductRepository products;
    @Autowired
    private FixtureUserInitializer initializer;
    @Autowired
    private DatabaseCleanUp cleanUp;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        initializer.initialize();
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @Test
    void createsDraftWithSnapshotAndNoPaymentFields() {
        Product product = products.save(new Product(brands.save(new Brand("브랜드")), "상품", 100, 5));
        ResponseEntity<JsonNode> response = request(HttpMethod.POST, "/api/v1/orders", "alice",
            "{\"items\":[{\"productId\":" + product.getId() + ",\"quantity\":2}]}");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode data = response.getBody().path("data");
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(data.path("orderId").asLong()).isPositive();
        assertThat(data.path("status").asText()).isEqualTo("DRAFT");
        assertThat(data.path("totalAmount").asLong()).isEqualTo(200);
        assertThat(data.path("items").get(0).path("productName").asText()).isEqualTo("상품");
        assertThat(data.has("paidAmount")).isFalse();
        assertThat(data.has("confirmedAt")).isFalse();
        assertThat(data.has("userId")).isFalse();
    }

    @Test
    void snapshotsSurvivePriceChangeAndDeletionAndConfirmationIsIdempotent() {
        Product product = product(100, 5);
        jdbc.update("UPDATE user SET point_balance = 1000 WHERE id=1");
        long id = create("alice", product.getId(), 2);
        long other = create("alice", product.getId(), 2);
        assertThat(other).isNotEqualTo(id);
        jdbc.update("UPDATE product SET name='새 이름', price=999 WHERE id=?", product.getId());

        JsonNode confirmed = success(request(HttpMethod.POST, "/api/v1/orders/" + id + "/confirm", "alice", null), 200);
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("paidAmount").asLong()).isEqualTo(200);
        assertThat(confirmed.path("items").get(0).path("productName").asText()).isEqualTo("상품");
        assertThat(confirmed.path("confirmedAt").asText()).isNotBlank();
        jdbc.update("UPDATE product SET deleted_at=UTC_TIMESTAMP(6) WHERE id=?", product.getId());
        var before = storedState();
        assertThat(success(request(HttpMethod.POST, "/api/v1/orders/" + id + "/confirm", "alice", null), 200))
            .isEqualTo(confirmed);
        assertThat(success(request(HttpMethod.GET, "/api/v1/orders/" + id, "alice", null), 200)).isEqualTo(confirmed);
        assertThat(storedState()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id=1", Long.class)).isEqualTo(800);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, product.getId()))
            .isEqualTo(3);
        failure(request(HttpMethod.POST, "/api/v1/orders/" + other + "/confirm", "alice", null),
            404, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.");
        assertThat(storedState()).isEqualTo(before);
    }

    @Test
    void isolatesCustomerOrdersAndAdminFiltersByExternalBuyerAndStatus() {
        Product product = product(100, 5);
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        long first = create("alice", product.getId(), 1);
        long second = create("alice", product.getId(), 1);
        long bob = create("bob", product.getId(), 1);
        success(request(HttpMethod.POST, "/api/v1/orders/" + first + "/confirm", "alice", null), 200);
        jdbc.update("UPDATE `order` SET created_at='2020-01-01 00:00:00'");

        JsonNode mine = success(request(HttpMethod.GET, "/api/v1/orders?size=1", "alice", null), 200);
        assertThat(mine.path("totalElements").asInt()).isEqualTo(2);
        assertThat(mine.path("totalPages").asInt()).isEqualTo(2);
        assertThat(mine.path("items").get(0).path("orderId").asLong()).isEqualTo(second);
        assertThat(mine.path("items").get(0).has("userId")).isFalse();
        assertThat(success(request(HttpMethod.GET, "/api/v1/orders?page=99", "alice", null), 200)
            .path("items")).isEmpty();
        JsonNode admin = success(request(HttpMethod.GET,
            "/api-admin/v1/orders?userId=alice&status=CONFIRMED", "admin", null), 200);
        assertThat(admin.path("totalElements").asInt()).isEqualTo(1);
        assertThat(admin.path("items").get(0).path("orderId").asLong()).isEqualTo(first);
        assertThat(admin.path("items").get(0).path("userId").asText()).isEqualTo("alice");
        assertThat(success(request(HttpMethod.GET, "/api-admin/v1/orders/" + bob, "admin", null), 200)
            .path("userId").asText()).isEqualTo("bob");
        assertThat(success(request(HttpMethod.GET, "/api-admin/v1/orders?userId=unknown", "admin", null), 200)
            .path("totalElements").asInt()).isZero();
        assertThat(success(request(HttpMethod.GET, "/api-admin/v1/orders", "admin", null), 200)
            .path("totalElements").asInt()).isEqualTo(3);
    }

    @Test
    void ownerAndMissingFailuresAreIdenticalAndAdminAuthorizationPrecedesBadInput() {
        Product product = product(100, 5);
        long id = create("alice", product.getId(), 1);
        var before = storedState();
        for (long requested : new long[]{id, Long.MAX_VALUE}) {
            failure(request(HttpMethod.GET, "/api/v1/orders/" + requested, "bob", null),
                404, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.");
            failure(request(HttpMethod.POST, "/api/v1/orders/" + requested + "/confirm", "bob", null),
                404, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.");
        }
        failure(request(HttpMethod.GET, "/api-admin/v1/orders/bad", "alice", null),
            403, "FORBIDDEN", "관리자 권한이 필요합니다.");
        failure(request(HttpMethod.GET, "/api-admin/v1/orders?status=bad&page=-1", "alice", null),
            403, "FORBIDDEN", "관리자 권한이 필요합니다.");
        failure(request(HttpMethod.GET, "/api-admin/v1/orders?status=bad", "admin", null),
            400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
        failure(request(HttpMethod.GET, "/api/v1/orders", null, null),
            400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
        failure(request(HttpMethod.GET, "/api/v1/orders", "unknown", null),
            404, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");
        assertThat(storedState()).isEqualTo(before);
    }

    @Test
    void rejectsMappedRequesterWhoseDatabaseRowIsMissing() {
        jdbc.update("DELETE FROM user WHERE id=1");
        failure(request(HttpMethod.GET, "/api/v1/orders", "alice", null),
            404, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");
        failure(request(HttpMethod.GET, "/api/v1/orders/" + Long.MAX_VALUE, "alice", null),
            404, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"items\":null}", "{\"items\":[]}", "{\"items\":{}}",
        "{\"items\":[null]}", "{\"items\":[{\"productId\":1}]}",
        "{\"items\":[{\"productId\":\"1\",\"quantity\":1}]}",
        "{\"items\":[{\"productId\":0,\"quantity\":1}]}",
        "{\"items\":[{\"productId\":9223372036854775808,\"quantity\":1}]}",
        "{\"items\":[{\"productId\":1,\"quantity\":\"1\"}]}",
        "{\"items\":[{\"productId\":1,\"quantity\":1.0}]}",
        "{\"items\":[{\"productId\":1,\"quantity\":0}]}",
        "{\"items\":[{\"productId\":1,\"quantity\":2147483648}]}",
        "{\"items\":[{\"productId\":1,\"quantity\":1,\"price\":100}]}",
        "{\"items\":[{\"productId\":1,\"quantity\":2147483647},{\"productId\":1,\"quantity\":1}]}"})
    void rejectsInvalidCreationInputBeforeResolvingRequesterAndPreservesState(String body) {
        var before = storedState();
        failure(request(HttpMethod.POST, "/api/v1/orders", "unknown", body),
            400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
        assertThat(storedState()).isEqualTo(before);
    }

    @Test
    void rejectsSubtotalAndTotalOverflowWithoutSavingAnyOrder() {
        Product maximum = product(Long.MAX_VALUE, 2);
        Product extra = product(1, 1);
        var before = storedState();
        failure(request(HttpMethod.POST, "/api/v1/orders", "alice", body(maximum.getId(), 2)),
            409, "ORDER_AMOUNT_LIMIT_EXCEEDED", "주문 금액의 허용 범위를 초과합니다.");
        failure(request(HttpMethod.POST, "/api/v1/orders", "alice", "{\"items\":[{\"productId\":"
            + maximum.getId() + ",\"quantity\":1},{\"productId\":" + extra.getId() + ",\"quantity\":1}]}"),
            409, "ORDER_AMOUNT_LIMIT_EXCEEDED", "주문 금액의 허용 범위를 초과합니다.");
        assertThat(storedState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesInsufficientStockOrPointsBeforeChangingAnyState(boolean stockFailure) {
        Product first = product(100, 5);
        Product second = product(100, stockFailure ? 0 : 5);
        if (stockFailure) {
            jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        }
        JsonNode order = success(request(HttpMethod.POST, "/api/v1/orders", "alice", "{\"items\":[{\"productId\":"
            + first.getId() + ",\"quantity\":1},{\"productId\":" + second.getId() + ",\"quantity\":1}]}"), 201);
        var before = storedState();
        failure(request(HttpMethod.POST, "/api/v1/orders/" + order.path("orderId").asLong() + "/confirm", "alice", null),
            409, stockFailure ? "INSUFFICIENT_STOCK" : "INSUFFICIENT_POINTS",
            stockFailure ? "상품 재고가 부족합니다." : "포인트가 부족합니다.");
        assertThat(storedState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void treatsCorruptStoredQuantityAsServerErrorWithoutChangingState(int quantity) {
        Product product = product(100, 5);
        long id = create("alice", product.getId(), 1);
        jdbc.update("UPDATE order_item SET quantity=? WHERE order_id=?", quantity, id);
        var before = storedState();
        failure(request(HttpMethod.POST, "/api/v1/orders/" + id + "/confirm", "alice", null),
            500, "INTERNAL_ERROR", "일시적인 오류가 발생했습니다.");
        assertThat(storedState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void treatsCorruptStoredAmountAsServerErrorWithoutChangingState(boolean confirm) {
        Product product = product(100, 5);
        jdbc.update("UPDATE user SET point_balance=1000 WHERE id=1");
        long id = create("alice", product.getId(), 2);
        jdbc.update("UPDATE order_item SET unit_price_snapshot=? WHERE order_id=?", Long.MAX_VALUE, id);
        var before = storedState();

        failure(request(confirm ? HttpMethod.POST : HttpMethod.GET,
            "/api/v1/orders/" + id + (confirm ? "/confirm" : ""), "alice", null),
            500, "INTERNAL_ERROR", "일시적인 오류가 발생했습니다.");

        assertThat(storedState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "bad", "1.5", "9223372036854775808"})
    void rejectsInvalidOrderIds(String id) {
        failure(request(HttpMethod.GET, "/api/v1/orders/" + id, "alice", null),
            400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
        failure(request(HttpMethod.POST, "/api/v1/orders/" + id + "/confirm", "alice", null),
            400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=1.5", "page=2147483648", "size=0", "size=101"})
    void rejectsInvalidPagination(String query) {
        failure(request(HttpMethod.GET, "/api/v1/orders?" + query, "alice", null),
            400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
        failure(request(HttpMethod.GET, "/api-admin/v1/orders?" + query, "admin", null),
            400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
    }

    private Product product(long price, int stock) {
        return products.save(new Product(brands.save(new Brand("브랜드")), "상품", price, stock));
    }

    private long create(String requester, long productId, int quantity) {
        return success(request(HttpMethod.POST, "/api/v1/orders", requester, body(productId, quantity)), 201)
            .path("orderId").asLong();
    }

    private String body(long productId, int quantity) {
        return "{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}";
    }

    private JsonNode success(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        return response.getBody().path("data");
    }

    private void failure(ResponseEntity<JsonNode> response, int status, String code, String message) {
        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().path("meta").path("message").asText()).isEqualTo(message);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"));
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String url, String requester, String body) {
        if (url.startsWith("/api-admin/")) {
            return AdminMockMvc.exchange(mvc, method, url, requester, body);
        }
        HttpHeaders headers = new HttpHeaders();
        if (requester != null) {
            headers.set("X-USER-ID", requester);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
