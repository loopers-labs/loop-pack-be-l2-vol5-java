package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.catalog.BrandModel;
import com.loopers.domain.catalog.ProductModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.ApiTestClient;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 주문 EP-10~13, EP-25~26 의 HTTP 계약. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ApiTestClient api;
    private UserModel user;
    private UserModel admin;
    private ProductModel product;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(restTemplate, objectMapper);
        user = fixtures.userWithBalance(10_000L);
        admin = fixtures.admin();
        BrandModel brand = fixtures.brand("브랜드");
        product = fixtures.product(brand.getId(), "상품", 1_000L, 5);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Map<String, Object> orderBody(Object productId, Object quantity) {
        return Map.of("items", List.of(Map.of("productId", productId, "quantity", quantity)));
    }

    @Nested
    @DisplayName("EP-10~13 고객 주문")
    class Customer {
        @DisplayName("EP-10 201 DRAFT → EP-11 200 CONFIRMED → EP-12/13 조회. 응답은 OrderResponse (userId 없음).")
        @Test
        void createConfirmList() {
            var created = api.post("/api/v1/orders", user.getId(), orderBody(product.getId(), 2)).assertSuccess(HttpStatus.CREATED);
            long orderId = created.data().path("id").asLong();
            assertThat(created.data().path("status").asText()).isEqualTo("DRAFT");
            assertThat(created.data().path("totalAmount").asLong()).isEqualTo(2_000L);
            assertThat(created.data().has("paidAmount")).isFalse();
            assertThat(created.data().has("userId")).isFalse();
            var item = created.data().path("items").get(0);
            assertThat(item.path("productId").asLong()).isEqualTo(product.getId());
            assertThat(item.path("unitPrice").asLong()).isEqualTo(1_000L);
            assertThat(item.path("lineAmount").asLong()).isEqualTo(2_000L);

            var confirmed = api.post("/api/v1/orders/" + orderId + "/confirm", user.getId(), null).assertSuccess(HttpStatus.OK);
            assertThat(confirmed.data().path("status").asText()).isEqualTo("CONFIRMED");
            assertThat(confirmed.data().path("paidAmount").asLong()).isEqualTo(2_000L);
            assertThat(confirmed.data().path("confirmedAt").asText()).isNotBlank();

            var list = api.get("/api/v1/orders?page=0&size=10", user.getId()).assertSuccess(HttpStatus.OK);
            assertThat(list.data().path("totalCount").asLong()).isEqualTo(1);

            var detail = api.get("/api/v1/orders/" + orderId, user.getId()).assertSuccess(HttpStatus.OK);
            assertThat(detail.data().path("id").asLong()).isEqualTo(orderId);
        }

        @DisplayName("[ER-12 EMPTY_ORDER_ITEMS] items 없음·빈 배열은 400.")
        @Test
        void emptyOrderItems() {
            api.post("/api/v1/orders", user.getId(), Map.of()).assertError(HttpStatus.BAD_REQUEST, "EMPTY_ORDER_ITEMS");
            api.post("/api/v1/orders", user.getId(), Map.of("items", List.of())).assertError(HttpStatus.BAD_REQUEST, "EMPTY_ORDER_ITEMS");
        }

        @DisplayName("[ER-04 PRODUCT_NOT_FOUND] 어느 품목의 상품이 없으면 404.")
        @Test
        void productNotFound() {
            api.post("/api/v1/orders", user.getId(), orderBody(999_999, 1)).assertError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        }

        @DisplayName("[ER-13 INVALID_QUANTITY] 수량 0 이하·누락·타입 오류는 400.")
        @Test
        void invalidQuantity() {
            api.post("/api/v1/orders", user.getId(), orderBody(product.getId(), 0)).assertError(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
            api.post("/api/v1/orders", user.getId(), Map.of("items", List.of(Map.of("productId", product.getId())))).assertError(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
            api.post("/api/v1/orders", user.getId(), orderBody(product.getId(), "many")).assertError(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
        }

        @DisplayName("[ER-14 AMOUNT_OUT_OF_RANGE] 합계가 표현 범위를 초과하면 400.")
        @Test
        void amountOutOfRange() {
            ProductModel expensive = fixtures.product(product.getBrandId(), "최고가", Long.MAX_VALUE, 10);

            api.post("/api/v1/orders", user.getId(), orderBody(expensive.getId(), 2)).assertError(HttpStatus.BAD_REQUEST, "AMOUNT_OUT_OF_RANGE");
        }

        @DisplayName("[ER-05 ORDER_NOT_FOUND] 없는 주문·형식 오류 경로의 확정·조회는 404.")
        @Test
        void orderNotFound() {
            api.post("/api/v1/orders/999999/confirm", user.getId(), null).assertError(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
            api.get("/api/v1/orders/abc", user.getId()).assertError(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        }

        @DisplayName("[ER-06 NOT_OWNER] 남의 주문 확정·조회는 403.")
        @Test
        void notOwner() {
            UserModel other = fixtures.userWithBalance(0L);
            OrderModel order = fixtures.draftOrder(other.getId(), product, 1);

            api.post("/api/v1/orders/" + order.getId() + "/confirm", user.getId(), null).assertError(HttpStatus.FORBIDDEN, "NOT_OWNER");
            api.get("/api/v1/orders/" + order.getId(), user.getId()).assertError(HttpStatus.FORBIDDEN, "NOT_OWNER");
        }

        @DisplayName("[ER-15 ORDER_NOT_DRAFT] 이미 확정된 주문 확정은 409.")
        @Test
        void orderNotDraft() {
            OrderModel order = fixtures.confirmedOrder(user.getId(), product, 1);

            api.post("/api/v1/orders/" + order.getId() + "/confirm", user.getId(), null).assertError(HttpStatus.CONFLICT, "ORDER_NOT_DRAFT");
        }

        @DisplayName("[ER-16 INSUFFICIENT_STOCK] 재고 부족은 409, message 에 부족한 productId.")
        @Test
        void insufficientStock() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 6);

            var result = api.post("/api/v1/orders/" + order.getId() + "/confirm", user.getId(), null).assertError(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK");

            assertThat(result.message()).contains("productId = " + product.getId());
        }

        @DisplayName("[ER-11 INSUFFICIENT_POINT] 잔액 부족은 409.")
        @Test
        void insufficientPoint() {
            UserModel poor = fixtures.userWithBalance(999L);
            OrderModel order = fixtures.draftOrder(poor.getId(), product, 1);

            api.post("/api/v1/orders/" + order.getId() + "/confirm", poor.getId(), null).assertError(HttpStatus.CONFLICT, "INSUFFICIENT_POINT");
        }

        @DisplayName("[ER-08 INVALID_PAGE] 내 주문 목록 페이지 오류는 400.")
        @Test
        void invalidPage() {
            api.get("/api/v1/orders?size=abc", user.getId()).assertError(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
    }

    @Nested
    @DisplayName("EP-25/26 관리자 주문")
    class Admin {
        @DisplayName("EP-25 Page<AdminOrderGroup>(userId, orders[]) / EP-26 AdminOrderResponse(userId 포함).")
        @Test
        void listAndDetail() {
            OrderModel order = fixtures.confirmedOrder(user.getId(), product, 2);

            var list = api.get("/api-admin/v1/orders?page=0&size=10", admin.getId()).assertSuccess(HttpStatus.OK);
            assertThat(list.data().path("totalCount").asLong()).isEqualTo(1);
            var group = list.data().path("items").get(0);
            assertThat(group.path("userId").asLong()).isEqualTo(user.getId());
            assertThat(group.path("orders").get(0).path("id").asLong()).isEqualTo(order.getId());
            assertThat(group.path("orders").get(0).path("userId").asLong()).isEqualTo(user.getId());

            var detail = api.get("/api-admin/v1/orders/" + order.getId(), admin.getId()).assertSuccess(HttpStatus.OK);
            assertThat(detail.data().path("userId").asLong()).isEqualTo(user.getId());
            assertThat(detail.data().path("paidAmount").asLong()).isEqualTo(2_000L);
            assertThat(detail.data().path("items").get(0).path("lineAmount").asLong()).isEqualTo(2_000L);
        }

        @DisplayName("[ER-05 ORDER_NOT_FOUND] / [ER-08 INVALID_PAGE] / [ER-02 NOT_ADMIN]")
        @Test
        void errors() {
            api.get("/api-admin/v1/orders/999999", admin.getId()).assertError(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
            api.get("/api-admin/v1/orders?page=-1", admin.getId()).assertError(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
            api.get("/api-admin/v1/orders", user.getId()).assertError(HttpStatus.FORBIDDEN, "NOT_ADMIN");
        }
    }
}
