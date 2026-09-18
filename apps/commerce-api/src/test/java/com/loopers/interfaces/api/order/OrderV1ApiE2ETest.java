package com.loopers.interfaces.api.order;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {

    private static final String ORDERS = "/api/v1/orders";
    private static final String POINTS = "/api/v1/points";
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    private final TestRestTemplate testRestTemplate;
    private final ProductService productService;
    private final PointService pointService;
    private final PointFacade pointFacade;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;

    private Long productId;

    @Autowired
    OrderV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        ProductService productService,
        PointService pointService,
        PointFacade pointFacade,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade
    ) {
        this.testRestTemplate = testRestTemplate;
        this.productService = productService;
        this.pointService = pointService;
        this.pointFacade = pointFacade;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        productId = productFacade.register(brandId, "코트", Price.of(1_000)).getId();
        productFacade.adjustStock(productId, Quantity.of(10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> ORDER =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<List<OrderV1Dto.OrderResponse>>> ORDERS_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Object>> ANY =
        new ParameterizedTypeReference<>() {};

    private HttpHeaders headers(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return headers;
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> place(Long userId, Object body) {
        return testRestTemplate.exchange(
            ORDERS, HttpMethod.POST, new HttpEntity<>(body, headers(userId)), ORDER);
    }

    private Object oneLine(int quantity) {
        return Map.of("lines", List.of(Map.of("productId", productId, "quantity", quantity)));
    }

    @Nested
    @DisplayName("접수")
    class Place {
        @DisplayName("접수하면 201 과 DRAFT 주문을 돌려준다. 재고·잔액은 그대로다")
        @Test
        void places() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = place(USER, oneLine(2));

            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(201),
                () -> assertThat(response.getBody().data().status()).isEqualTo("DRAFT"),
                () -> assertThat(response.getBody().data().totalAmount()).isEqualTo(2_000),
                () -> assertThat(response.getBody().data().paidAmount()).isNull(),
                () -> assertThat(response.getBody().data().items()).hasSize(1),
                () -> assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(10)),
                () -> assertThat(pointService.getBalance(USER)).isEqualTo(Money.of(50_000))
            );
        }

        @DisplayName("ORDER-004 · 같은 상품이 두 줄로 오면 한 품목으로 합산된다")
        @Test
        void mergesSameProduct() {
            Object body = Map.of("lines", List.of(
                Map.of("productId", productId, "quantity", 2),
                Map.of("productId", productId, "quantity", 3)));

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = place(USER, body);

            assertAll(
                () -> assertThat(response.getBody().data().items()).hasSize(1),
                () -> assertThat(response.getBody().data().items().get(0).quantity()).isEqualTo(5),
                () -> assertThat(response.getBody().data().totalAmount()).isEqualTo(5_000)
            );
        }

        @DisplayName("ORDER-001 · 품목이 없으면 400 이다")
        @Test
        void rejectsEmptyLines() {
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ORDERS, HttpMethod.POST,
                new HttpEntity<>(Map.of("lines", List.of()), headers(USER)), ANY);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @DisplayName("ORDER-002 · 수량이 0이면 400 이다")
        @Test
        void rejectsZeroQuantity() {
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ORDERS, HttpMethod.POST,
                new HttpEntity<>(Map.of("lines", List.of(
                    Map.of("productId", productId, "quantity", 0))), headers(USER)), ANY);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @DisplayName("ORDER-003 · 삭제된 상품은 404 다")
        @Test
        void rejectsDeletedProduct() {
            productFacade.delete(productId);

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ORDERS, HttpMethod.POST, new HttpEntity<>(oneLine(1), headers(USER)), ANY);

            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(404),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("PRODUCT_NOT_FOUND")
            );
        }
    }

    @Nested
    @DisplayName("확정")
    class Confirm {
        private Long placed(Long userId, int quantity) {
            return place(userId, oneLine(quantity)).getBody().data().id();
        }

        @DisplayName("확정하면 200 · CONFIRMED · 결제액이 담기고, 재고와 잔액이 줄어든다")
        @Test
        void confirms() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);
            Long orderId = placed(USER, 2);

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ORDERS + "/" + orderId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(null, headers(USER)), ORDER);

            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(200),
                () -> assertThat(response.getBody().data().status()).isEqualTo("CONFIRMED"),
                () -> assertThat(response.getBody().data().paidAmount()).isEqualTo(2_000),
                () -> assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(8)),
                () -> assertThat(pointService.getBalance(USER)).isEqualTo(Money.of(48_000))
            );
        }

        @DisplayName("ORDER-010 · 이미 확정된 주문은 409 · ORDER_NOT_DRAFT 다")
        @Test
        void rejectsSecondConfirm() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);
            Long orderId = placed(USER, 1);
            testRestTemplate.exchange(ORDERS + "/" + orderId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(null, headers(USER)), ANY);

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ORDERS + "/" + orderId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(null, headers(USER)), ANY);

            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(409),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("ORDER_NOT_DRAFT")
            );
        }

        @DisplayName("ORDER-014 · 잔액이 모자라면 409 이고 재고도 그대로다")
        @Test
        void rejectsWhenPointIsShort() {
            pointFacade.charge(USER, ChargeAmount.of(1_000), NOW);
            Long orderId = placed(USER, 2);

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ORDERS + "/" + orderId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(null, headers(USER)), ANY);

            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(409),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INSUFFICIENT_POINT"),
                () -> assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(10)),
                () -> assertThat(pointService.getBalance(USER)).isEqualTo(Money.of(1_000))
            );
        }

        @DisplayName("ORDER-011 · 남의 주문은 404 다. 존재를 숨긴다")
        @Test
        void rejectsForeignOrder() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);
            Long orderId = placed(USER, 1);

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                ORDERS + "/" + orderId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(null, headers(OTHER_USER)), ANY);

            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(404),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("ORDER_NOT_FOUND")
            );
        }
    }

    @Nested
    @DisplayName("조회 — ORDER-020 · 022")
    class Query {
        @DisplayName("내 주문 목록은 내 것만 돌려준다")
        @Test
        void listsOnlyMine() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);
            Long mine = place(USER, oneLine(1)).getBody().data().id();
            place(OTHER_USER, oneLine(1));

            List<OrderV1Dto.OrderResponse> orders = testRestTemplate.exchange(
                ORDERS, HttpMethod.GET, new HttpEntity<>(null, headers(USER)), ORDERS_TYPE)
                .getBody().data();

            assertThat(orders).extracting(OrderV1Dto.OrderResponse::id).containsExactly(mine);
        }

        @DisplayName("ORDER-022 · 접수 후 상품명이 바뀌어도 주문에는 접수 시점 이름이 남는다")
        @Test
        void keepsNameAtPlacement() {
            Long orderId = place(USER, oneLine(1)).getBody().data().id();
            productFacade.update(productId, "트렌치코트", Price.of(2_000));

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ORDERS + "/" + orderId, HttpMethod.GET, new HttpEntity<>(null, headers(USER)), ORDER);

            assertAll(
                () -> assertThat(response.getBody().data().items().get(0).productName()).isEqualTo("코트"),
                () -> assertThat(response.getBody().data().items().get(0).unitPrice()).isEqualTo(1_000),
                () -> assertThat(response.getBody().data().totalAmount()).isEqualTo(1_000)
            );
        }
    }

    @Nested
    @DisplayName("연결 흐름 — 충전 → 주문 확정 → 잔액")
    class EndToEndFlow {
        @DisplayName("잔액 0에서 10,000원을 충전하고 7,000원을 결제하면 잔액은 3,000원이다")
        @Test
        void chargeThenOrderThenBalance() {
            testRestTemplate.exchange(POINTS + "/charge", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 10_000), headers(USER)), ANY);

            Object body = Map.of("lines", List.of(
                Map.of("productId", productId, "quantity", 7)));
            Long orderId = place(USER, body).getBody().data().id();

            testRestTemplate.exchange(ORDERS + "/" + orderId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(null, headers(USER)), ANY);

            ResponseEntity<ApiResponse<Map<String, Object>>> balance = testRestTemplate.exchange(
                POINTS, HttpMethod.GET, new HttpEntity<>(null, headers(USER)),
                new ParameterizedTypeReference<>() {});

            assertAll(
                () -> assertThat(((Number) balance.getBody().data().get("balance")).longValue()).isEqualTo(3_000),
                () -> assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(3))
            );
        }
    }
}
