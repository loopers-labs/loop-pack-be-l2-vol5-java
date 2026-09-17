package com.loopers.interfaces.api.order;

import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("주문 API 는 주문 생성·확정과 내 주문 조회를 제공한다.")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {

    private static final String ENDPOINT = "/api/v1/orders";

    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> ORDER_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<PageResponse<OrderV1Dto.OrderResponse>>> PAGE_TYPE =
        new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate testRestTemplate;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private OrderService orderService;
    @Autowired
    private PointService pointService;
    @Autowired
    private OrderJpaRepository orderJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private PointJpaRepository pointJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpEntity<Object> request(Object body, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-USER-ID", String.valueOf(userId));
        }
        return new HttpEntity<>(body, headers);
    }

    private Object itemsBody(Object... productIdAndQuantity) {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < productIdAndQuantity.length; i += 2) {
            items.add(Map.of("productId", productIdAndQuantity[i], "quantity", productIdAndQuantity[i + 1]));
        }
        return Map.of("items", items);
    }

    @DisplayName("POST /api/v1/orders")
    @Nested
    class Create {
        @DisplayName("여러 품목을 DRAFT 로 저장하고 201 과 품목·총액을 반환하며 결제 정보는 비어 있다.")
        @Test
        void createsDraftOrder() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT, HttpMethod.POST,
                request(itemsBody(shirt.getId(), 2, pants.getId(), 1), user.getId()), ORDER_TYPE);

            OrderV1Dto.OrderResponse body = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                () -> assertThat(body.status()).isEqualTo("DRAFT"),
                () -> assertThat(body.orderTotal()).isEqualTo(7_000L),
                () -> assertThat(body.usedPointAmount()).isNull(),
                () -> assertThat(body.paymentAmount()).isNull(),
                () -> assertThat(body.items()).hasSize(2),
                () -> assertThat(orderJpaRepository.findById(body.id()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(productJpaRepository.findById(shirt.getId()).orElseThrow().getStockQuantity())
                    .isEqualTo(5L)
            );
        }

        @DisplayName("품목이 없으면 400 INVALID_ORDER_ITEMS 로 거절하고 주문을 저장하지 않는다.")
        @Test
        void rejectsEmptyItems() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT, HttpMethod.POST, request(Map.of("items", List.of()), user.getId()), ORDER_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_ORDER_ITEMS"),
                () -> assertThat(orderJpaRepository.findAll()).isEmpty()
            );
        }

        @DisplayName("수량이 0 이면 400 INVALID_ORDER_QUANTITY 로 거절한다.")
        @Test
        void rejectsZeroQuantity() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT, HttpMethod.POST, request(itemsBody(shirt.getId(), 0), user.getId()), ORDER_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_ORDER_QUANTITY"),
                () -> assertThat(orderJpaRepository.findAll()).isEmpty()
            );
        }

        @DisplayName("존재하지 않는 상품은 404 PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownProduct() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT, HttpMethod.POST, request(itemsBody(999_999L, 1), user.getId()), ORDER_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("PRODUCT_NOT_FOUND")
            );
        }

        @DisplayName("X-USER-ID 가 없으면 400 INVALID_REQUEST 로 거절한다.")
        @Test
        void rejectsMissingCustomerId() {
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT, HttpMethod.POST, request(itemsBody(shirt.getId(), 1), null), ORDER_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_REQUEST"),
                () -> assertThat(orderJpaRepository.findAll()).isEmpty()
            );
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm")
    @Nested
    class Confirm {
        @DisplayName("본인의 DRAFT 주문을 확정해 재고·포인트를 차감하고 CONFIRMED 와 결제 정보를 반환한다.")
        @Test
        void confirmsOrder() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
            OrderModel order = orderService.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L), new OrderItemCommand(pants.getId(), 1L)));

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT + "/" + order.getId() + "/confirm", HttpMethod.POST,
                request(null, user.getId()), ORDER_TYPE);

            OrderV1Dto.OrderResponse body = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(body.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(body.orderTotal()).isEqualTo(7_000L),
                () -> assertThat(body.usedPointAmount()).isEqualTo(7_000L),
                () -> assertThat(body.paymentAmount()).isEqualTo(7_000L),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance())
                    .isEqualTo(3_000L),
                () -> assertThat(productJpaRepository.findById(shirt.getId()).orElseThrow().getStockQuantity())
                    .isEqualTo(3L)
            );
        }

        @DisplayName("포인트가 부족하면 409 INSUFFICIENT_POINT 로 거절하고 상태를 유지한다.")
        @Test
        void rejectsInsufficientPoint() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 1_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT + "/" + order.getId() + "/confirm", HttpMethod.POST,
                request(null, user.getId()), ORDER_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INSUFFICIENT_POINT"),
                () -> assertThat(orderJpaRepository.findById(order.getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance())
                    .isEqualTo(1_000L),
                () -> assertThat(productJpaRepository.findById(shirt.getId()).orElseThrow().getStockQuantity())
                    .isEqualTo(5L)
            );
        }

        @DisplayName("재고가 부족하면 409 INSUFFICIENT_STOCK 로 거절하고 상태를 유지한다.")
        @Test
        void rejectsInsufficientStock() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 100_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 1L);
            OrderModel order = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT + "/" + order.getId() + "/confirm", HttpMethod.POST,
                request(null, user.getId()), ORDER_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INSUFFICIENT_STOCK"),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance())
                    .isEqualTo(100_000L),
                () -> assertThat(productJpaRepository.findById(shirt.getId()).orElseThrow().getStockQuantity())
                    .isEqualTo(1L)
            );
        }

        @DisplayName("이미 확정한 주문은 409 ORDER_NOT_CONFIRMABLE 로 거절한다.")
        @Test
        void rejectsAlreadyConfirmed() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));
            testRestTemplate.exchange(ENDPOINT + "/" + order.getId() + "/confirm", HttpMethod.POST,
                request(null, user.getId()), ORDER_TYPE);

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT + "/" + order.getId() + "/confirm", HttpMethod.POST,
                request(null, user.getId()), ORDER_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("ORDER_NOT_CONFIRMABLE"),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance())
                    .isEqualTo(6_000L)
            );
        }

        @DisplayName("다른 사용자의 주문 확정은 404 ORDER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsOtherUsersOrder() {
            UserModel owner = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            pointService.charge(other.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(owner.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT + "/" + order.getId() + "/confirm", HttpMethod.POST,
                request(null, other.getId()), ORDER_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("ORDER_NOT_FOUND"),
                () -> assertThat(pointJpaRepository.findByUserId(other.getId()).orElseThrow().getBalance())
                    .isEqualTo(10_000L)
            );
        }
    }

    @DisplayName("GET /api/v1/orders")
    @Nested
    class GetOrders {
        @DisplayName("자신의 주문만 품목과 함께 페이지로 반환한다.")
        @Test
        void returnsOwnOrderPage() {
            UserModel me = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 100L);
            ProductModel pants = productFixture.createProduct("바지", 2_000L, 100L);
            orderService.create(me.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 1L), new OrderItemCommand(pants.getId(), 2L)));
            orderService.create(other.getId(), List.of(new OrderItemCommand(shirt.getId(), 1L)));

            ResponseEntity<ApiResponse<PageResponse<OrderV1Dto.OrderResponse>>> response =
                testRestTemplate.exchange(ENDPOINT, HttpMethod.GET, request(null, me.getId()), PAGE_TYPE);

            PageResponse<OrderV1Dto.OrderResponse> body = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(body.totalElements()).isEqualTo(1L),
                () -> assertThat(body.page()).isZero(),
                () -> assertThat(body.size()).isEqualTo(20),
                () -> assertThat(body.totalPages()).isEqualTo(1),
                () -> assertThat(body.items()).hasSize(1),
                () -> assertThat(body.items().get(0).items()).hasSize(2)
            );
        }

        @DisplayName("size 가 100 을 넘으면 400 INVALID_PAGE_REQUEST 로 거절한다.")
        @Test
        void rejectsInvalidPageSize() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<PageResponse<OrderV1Dto.OrderResponse>>> response =
                testRestTemplate.exchange(ENDPOINT + "?size=101", HttpMethod.GET,
                    request(null, user.getId()), PAGE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_PAGE_REQUEST")
            );
        }

        @DisplayName("지원하지 않는 정렬은 400 INVALID_SORT 로 거절한다.")
        @Test
        void rejectsUnsupportedSort() {
            UserModel user = userFixture.createUserWithPoint();

            ResponseEntity<ApiResponse<PageResponse<OrderV1Dto.OrderResponse>>> response =
                testRestTemplate.exchange(ENDPOINT + "?sort=price_asc", HttpMethod.GET,
                    request(null, user.getId()), PAGE_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("INVALID_SORT")
            );
        }

        @DisplayName("size=2, sort=oldest 는 먼저 만든 두 건과 전체 자원 수를 반환한다.")
        @Test
        void returnsOldestFirstPage() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 100L);
            OrderModel first = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 1L)));
            OrderModel second = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 1L)));
            orderService.create(user.getId(), List.of(new OrderItemCommand(shirt.getId(), 1L)));

            ResponseEntity<ApiResponse<PageResponse<OrderV1Dto.OrderResponse>>> response =
                testRestTemplate.exchange(ENDPOINT + "?page=0&size=2&sort=oldest", HttpMethod.GET,
                    request(null, user.getId()), PAGE_TYPE);

            PageResponse<OrderV1Dto.OrderResponse> body = response.getBody().data();
            assertAll(
                () -> assertThat(body.items()).extracting(OrderV1Dto.OrderResponse::id)
                    .containsExactly(first.getId(), second.getId()),
                () -> assertThat(body.totalElements()).isEqualTo(3L),
                () -> assertThat(body.totalPages()).isEqualTo(2)
            );
        }
    }

    @DisplayName("GET /api/v1/orders/{orderId}")
    @Nested
    class GetOrder {
        @DisplayName("DRAFT 주문은 총액을 반환하고 포인트 사용액·결제액은 null 이다.")
        @Test
        void returnsDraftOrderDetail() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT + "/" + order.getId(), HttpMethod.GET, request(null, user.getId()), ORDER_TYPE);

            OrderV1Dto.OrderResponse body = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(body.status()).isEqualTo("DRAFT"),
                () -> assertThat(body.orderTotal()).isEqualTo(4_000L),
                () -> assertThat(body.usedPointAmount()).isNull(),
                () -> assertThat(body.paymentAmount()).isNull(),
                () -> assertThat(body.items()).hasSize(1),
                () -> assertThat(body.items().get(0).productId()).isEqualTo(shirt.getId()),
                () -> assertThat(body.items().get(0).quantity()).isEqualTo(2L),
                () -> assertThat(body.items().get(0).unitPrice()).isEqualTo(2_000L),
                () -> assertThat(body.items().get(0).amount()).isEqualTo(4_000L)
            );
        }

        @DisplayName("다른 사용자의 주문 상세는 404 ORDER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsOtherUsersOrder() {
            UserModel owner = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(owner.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 1L)));

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT + "/" + order.getId(), HttpMethod.GET, request(null, other.getId()), ORDER_TYPE);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("ORDER_NOT_FOUND")
            );
        }
    }
}
