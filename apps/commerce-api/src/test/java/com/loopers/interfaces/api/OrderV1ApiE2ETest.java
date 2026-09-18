package com.loopers.interfaces.api;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.order.OrderV1Dto;
import com.loopers.interfaces.api.point.PointV1Dto;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public OrderV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpEntity<Object> withUser(Long userId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private ProductModel saveProduct(Long price, int stock) {
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키"));
        return productJpaRepository.save(new ProductModel(brand.getId(), "runner", price, stock));
    }

    private void charge(Long userId, Long amount) {
        testRestTemplate.exchange("/api/v1/points/charge", HttpMethod.POST,
            withUser(userId, new PointV1Dto.ChargeRequest(amount)), Void.class);
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> createOrder(Long userId, List<OrderV1Dto.OrderItemRequest> items) {
        return testRestTemplate.exchange("/api/v1/orders", HttpMethod.POST,
            withUser(userId, new OrderV1Dto.CreateRequest(items)), new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmOrder(Long userId, Long orderId) {
        return testRestTemplate.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            withUser(userId, null), new ParameterizedTypeReference<>() {});
    }

    private Long getBalance(Long userId) {
        ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
            "/api/v1/points", HttpMethod.GET, withUser(userId, null), new ParameterizedTypeReference<>() {});
        return response.getBody().data().balance();
    }

    @DisplayName("대표 흐름: 포인트 충전 → 주문 생성 → 확정")
    @Nested
    class RepresentativeFlow {
        @DisplayName("잔액을 충전한 뒤 주문을 확정하면, 재고가 줄고 잔액이 결제액만큼 차감된다.")
        @Test
        void completesOrder_endToEnd() {
            // arrange
            ProductModel product = saveProduct(7_000L, 5);
            charge(1L, 10_000L);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> createResponse =
                createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)));
            Long orderId = createResponse.getBody().data().id();

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmResponse = confirmOrder(1L, orderId);

            // assert
            assertThat(createResponse.getBody().data().status().name()).isEqualTo("DRAFT");
            assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(confirmResponse.getBody().data().status().name()).isEqualTo("CONFIRMED");
            assertThat(confirmResponse.getBody().data().paidAmount()).isEqualTo(7_000L);
            assertThat(getBalance(1L)).isEqualTo(3_000L);

            ProductModel afterConfirm = productJpaRepository.findById(product.getId()).orElseThrow();
            assertThat(afterConfirm.getStock()).isEqualTo(4);
        }
    }

    @DisplayName("POST /api/v1/orders")
    @Nested
    class Create {
        @DisplayName("존재하지 않는 상품을 담으면, 404를 응답한다.")
        @Test
        void returns404_whenProductDoesNotExist() {
            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(999L, 1)));

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("수량이 0 이하이면, 400을 응답한다.")
        @Test
        void returns400_whenQuantityIsNotPositive() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 0)));

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("같은 상품이 여러 줄로 담기면, 수량을 합산한 하나의 품목으로 저장된다.")
        @Test
        void mergesDuplicateProductLines() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(1L, List.of(
                new OrderV1Dto.OrderItemRequest(product.getId(), 2),
                new OrderV1Dto.OrderItemRequest(product.getId(), 1)
            ));

            // assert
            assertThat(response.getBody().data().items()).hasSize(1);
            assertThat(response.getBody().data().items().get(0).quantity()).isEqualTo(3);
            assertThat(response.getBody().data().totalAmount()).isEqualTo(30_000L);
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm")
    @Nested
    class Confirm {
        @DisplayName("본인 소유가 아닌 주문을 확정하려 하면, 404를 응답한다.")
        @Test
        void returns404_whenNotOwner() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            charge(1L, 10_000L);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(2L, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("재고가 부족하면, 400을 응답하고 재고와 잔액이 그대로 유지된다.")
        @Test
        void returns400_andRollsBack_whenStockIsInsufficient() {
            // arrange
            ProductModel product = saveProduct(10_000L, 1);
            charge(1L, 100_000L);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 2)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(1L, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(getBalance(1L)).isEqualTo(100_000L);
            ProductModel unchanged = productJpaRepository.findById(product.getId()).orElseThrow();
            assertThat(unchanged.getStock()).isEqualTo(1);
        }

        @DisplayName("포인트 잔액이 부족하면, 409를 응답하고 재고가 그대로 유지된다.")
        @Test
        void returns409_andRollsBack_whenBalanceIsInsufficient() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            charge(1L, 1_000L);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(1L, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(getBalance(1L)).isEqualTo(1_000L);
            ProductModel unchanged = productJpaRepository.findById(product.getId()).orElseThrow();
            assertThat(unchanged.getStock()).isEqualTo(5);
        }
    }

    @DisplayName("GET /api/v1/orders, GET /api/v1/orders/{orderId}")
    @Nested
    class Get {
        @DisplayName("본인의 주문 목록을 조회하면, 다른 사용자의 주문은 포함되지 않는다.")
        @Test
        void excludesOtherUsersOrders() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)));
            createOrder(2L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)));

            // act
            ResponseEntity<ApiResponse<List<OrderV1Dto.OrderResponse>>> response = testRestTemplate.exchange(
                "/api/v1/orders", HttpMethod.GET, withUser(1L, null), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(response.getBody().data()).hasSize(1);
        }

        @DisplayName("다른 사용자의 주문 상세를 조회하면, 404를 응답한다.")
        @Test
        void returns404_whenNotOwner() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                "/api/v1/orders/" + orderId, HttpMethod.GET, withUser(2L, null), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
