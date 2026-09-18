package com.loopers.interfaces.api.order;

import com.loopers.domain.point.Point;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final String ENDPOINT_ORDERS = "/api/v1/orders";

    private final TestRestTemplate testRestTemplate;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final PointJpaRepository pointJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public OrderV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        PointJpaRepository pointJpaRepository,
        OrderJpaRepository orderJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.pointJpaRepository = pointJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api/v1/orders")
    @Nested
    class CreateOrder {
        @DisplayName("상품과 수량이 유효하면, 201과 DRAFT 주문을 받고 재고는 차감되지 않는다.")
        @Test
        void returnsDraftOrderAndKeepsStock_whenRequestIsValid() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 10);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                createOrder(userId, "[{\"productId\":" + productId + ",\"quantity\":2}]");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().data().status()).isEqualTo("DRAFT");
            assertThat(response.getBody().data().totalAmount()).isEqualTo(2000L);
            assertThat(productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity())
                .isEqualTo(10);
        }

        @DisplayName("같은 상품을 여러 품목으로 주문하면, 수량이 합산된 하나의 품목이 된다.")
        @Test
        void mergesSameProduct() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 10);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(userId,
                "[{\"productId\":" + productId + ",\"quantity\":2},"
                    + "{\"productId\":" + productId + ",\"quantity\":3}]");

            // assert
            assertThat(response.getBody().data().items()).hasSize(1);
            assertThat(response.getBody().data().items().get(0).quantity()).isEqualTo(5);
            assertThat(response.getBody().data().totalAmount()).isEqualTo(5000L);
        }

        @DisplayName("없는 상품을 주문하면, 404 응답을 받고 주문이 저장되지 않는다.")
        @Test
        void returnsNotFound_whenProductIsAbsent() {
            // arrange
            Long userId = saveUser();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                createOrder(userId, "[{\"productId\":-1,\"quantity\":2}]");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(orderJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("삭제된 상품을 주문하면, 404 응답을 받고 주문이 저장되지 않는다.")
        @Test
        void returnsNotFound_whenProductIsDeleted() {
            // arrange
            Long userId = saveUser();
            Product product = new Product(1L, "삭제된 상품", new Price(1000L));
            product.delete();
            Long productId = productJpaRepository.save(product).getId();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                createOrder(userId, "[{\"productId\":" + productId + ",\"quantity\":2}]");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(orderJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("수량이 0 이하이면, 400 응답을 받는다.")
        @Test
        void returnsBadRequest_whenQuantityIsNotPositive() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 10);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                createOrder(userId, "[{\"productId\":" + productId + ",\"quantity\":0}]");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("식별 헤더가 없으면, 401 응답을 받는다.")
        @Test
        void returnsUnauthorized_whenHeaderIsMissing() {
            // arrange
            Long productId = saveProduct(1000L, 10);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                createOrder(null, "[{\"productId\":" + productId + ",\"quantity\":2}]");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm")
    @Nested
    class ConfirmOrder {
        @DisplayName("재고와 포인트가 충분하면, 200과 CONFIRMED 주문을 받고 재고·포인트가 차감된다.")
        @Test
        void confirmsOrder_whenStockAndPointAreSufficient() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 10);
            savePoint(userId, 5000L);
            Long orderId = createOrder(userId, "[{\"productId\":" + productId + ",\"quantity\":2}]")
                .getBody().data().orderId();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirm(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().status()).isEqualTo("CONFIRMED");
            assertThat(response.getBody().data().paidAmount()).isEqualTo(2000L);
            assertThat(productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity())
                .isEqualTo(8);
            assertThat(pointJpaRepository.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(3000L);
        }

        @DisplayName("재고가 부족하면, 409 응답을 받고 주문은 DRAFT 로 남고 포인트도 차감되지 않는다.")
        @Test
        void keepsEverything_whenStockIsInsufficient() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 1);
            savePoint(userId, 5000L);
            Long orderId = createOrder(userId, "[{\"productId\":" + productId + ",\"quantity\":2}]")
                .getBody().data().orderId();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirm(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus().name()).isEqualTo("DRAFT");
            assertThat(productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity()).isEqualTo(1);
            assertThat(pointJpaRepository.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(5000L);
        }

        @DisplayName("포인트가 부족하면, 409 응답을 받고 차감된 재고가 롤백된다.")
        @Test
        void rollsBackStock_whenPointIsInsufficient() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 10);
            savePoint(userId, 500L);
            Long orderId = createOrder(userId, "[{\"productId\":" + productId + ",\"quantity\":2}]")
                .getBody().data().orderId();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirm(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus().name()).isEqualTo("DRAFT");
            assertThat(productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity())
                .isEqualTo(10);
        }

        @DisplayName("이미 확정된 주문을 다시 확정하면, 409 응답을 받는다.")
        @Test
        void returnsConflict_whenAlreadyConfirmed() {
            // arrange
            Long userId = saveUser();
            Long productId = saveProduct(1000L, 10);
            savePoint(userId, 5000L);
            Long orderId = createOrder(userId, "[{\"productId\":" + productId + ",\"quantity\":2}]")
                .getBody().data().orderId();
            confirm(userId, orderId);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirm(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @DisplayName("남의 주문을 확정하려 하면, 404 응답을 받는다.")
        @Test
        void returnsNotFound_whenRequesterIsNotOwner() {
            // arrange
            Long ownerId = saveUser();
            Long otherId = saveUser();
            Long productId = saveProduct(1000L, 10);
            savePoint(otherId, 5000L);
            Long orderId = createOrder(ownerId, "[{\"productId\":" + productId + ",\"quantity\":2}]")
                .getBody().data().orderId();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirm(otherId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("GET /api/v1/orders")
    @Nested
    class GetOrders {
        @DisplayName("본인 주문만 목록에 포함된다.")
        @Test
        void returnsOnlyOwnOrders() {
            // arrange
            Long userId = saveUser();
            Long otherId = saveUser();
            Long productId = saveProduct(1000L, 10);
            createOrder(userId, "[{\"productId\":" + productId + ",\"quantity\":2}]");
            createOrder(otherId, "[{\"productId\":" + productId + ",\"quantity\":1}]");

            // act
            ParameterizedTypeReference<ApiResponse<java.util.List<OrderV1Dto.OrderResponse>>> type =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<java.util.List<OrderV1Dto.OrderResponse>>> response =
                testRestTemplate.exchange(ENDPOINT_ORDERS, HttpMethod.GET,
                    new HttpEntity<>(null, headers(userId)), type);

            // assert
            assertThat(response.getBody().data()).hasSize(1);
            assertThat(response.getBody().data().get(0).userId()).isEqualTo(userId);
        }

        @DisplayName("남의 주문 상세를 조회하면, 404 응답을 받는다.")
        @Test
        void returnsNotFound_whenRequesterIsNotOwner() {
            // arrange
            Long ownerId = saveUser();
            Long otherId = saveUser();
            Long productId = saveProduct(1000L, 10);
            Long orderId = createOrder(ownerId, "[{\"productId\":" + productId + ",\"quantity\":2}]")
                .getBody().data().orderId();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                ENDPOINT_ORDERS + "/" + orderId, HttpMethod.GET,
                new HttpEntity<>(null, headers(otherId)), responseType());

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private Long saveUser() {
        return userJpaRepository.save(new User()).getId();
    }

    private Long saveProduct(long price, int stock) {
        Product product = new Product(1L, "상품", new Price(price));
        product.changeStock(stock);
        return productJpaRepository.save(product).getId();
    }

    private void savePoint(Long userId, long balance) {
        Point point = new Point(userId);
        point.charge(balance);
        pointJpaRepository.save(point);
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> createOrder(Long userId, String itemsJson) {
        HttpHeaders headers = headers(userId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"items\":" + itemsJson + "}";

        return testRestTemplate.exchange(
            ENDPOINT_ORDERS, HttpMethod.POST, new HttpEntity<>(body, headers), responseType());
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirm(Long userId, Long orderId) {
        return testRestTemplate.exchange(
            ENDPOINT_ORDERS + "/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(null, headers(userId)), responseType());
    }

    private ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> responseType() {
        return new ParameterizedTypeReference<>() {};
    }

    private HttpHeaders headers(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, String.valueOf(userId));
        }
        return headers;
    }
}
