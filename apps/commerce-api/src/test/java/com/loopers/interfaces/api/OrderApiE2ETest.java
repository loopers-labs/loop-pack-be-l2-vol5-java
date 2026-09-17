package com.loopers.interfaces.api;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.order.OrderDto;
import com.loopers.interfaces.api.point.PointDto;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final String ORDERS_ENDPOINT = "/api/v1/orders";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    private User user;
    private Product bag;
    private Product cap;

    @Autowired
    OrderApiE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        UserJpaRepository userJpaRepository,
        OrderJpaRepository orderJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new User("user1"));
        Brand brand = brandJpaRepository.save(new Brand("루퍼스"));
        bag = productJpaRepository.save(new Product(brand.getId(), "가방", 3_000L, 5L));
        cap = productJpaRepository.save(new Product(brand.getId(), "모자", 1_000L, 5L));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // 가방 3,000원×2 + 모자 1,000원×1 = 7,000원
    private OrderDto.CreateRequest bagTwoAndCapOne() {
        return new OrderDto.CreateRequest(List.of(
            new OrderDto.ItemRequest(bag.getId(), 2L),
            new OrderDto.ItemRequest(cap.getId(), 1L)
        ));
    }

    private HttpHeaders headersOf(User requester) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(USER_ID_HEADER, String.valueOf(requester.getId()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<ApiResponse<OrderDto.OrderResponse>> createOrder(User requester, OrderDto.CreateRequest request) {
        ParameterizedTypeReference<ApiResponse<OrderDto.OrderResponse>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(ORDERS_ENDPOINT, HttpMethod.POST, new HttpEntity<>(request, headersOf(requester)), responseType);
    }

    private ResponseEntity<ApiResponse<OrderDto.OrderResponse>> confirmOrder(User requester, Long orderId) {
        ParameterizedTypeReference<ApiResponse<OrderDto.OrderResponse>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(ORDERS_ENDPOINT + "/" + orderId + "/confirm", HttpMethod.POST, new HttpEntity<>(headersOf(requester)), responseType);
    }

    private ResponseEntity<ApiResponse<OrderDto.OrderResponse>> getOrder(User requester, Long orderId) {
        ParameterizedTypeReference<ApiResponse<OrderDto.OrderResponse>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(ORDERS_ENDPOINT + "/" + orderId, HttpMethod.GET, new HttpEntity<>(headersOf(requester)), responseType);
    }

    private ResponseEntity<ApiResponse<PageResponse<OrderDto.OrderResponse>>> getOrders(User requester, String query) {
        ParameterizedTypeReference<ApiResponse<PageResponse<OrderDto.OrderResponse>>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(ORDERS_ENDPOINT + query, HttpMethod.GET, new HttpEntity<>(headersOf(requester)), responseType);
    }

    private Long createdOrderId(User requester, OrderDto.CreateRequest request) {
        return createOrder(requester, request).getBody().data().orderId();
    }

    private User chargedUser(User target, long amount) {
        User saved = userJpaRepository.findById(target.getId()).orElseThrow();
        saved.charge(amount);
        return userJpaRepository.save(saved);
    }

    private void changePrice(Product product, long price) {
        Product saved = productJpaRepository.findById(product.getId()).orElseThrow();
        saved.update(saved.getName(), price);
        productJpaRepository.save(saved);
    }

    private void deleteProduct(Product product) {
        Product saved = productJpaRepository.findById(product.getId()).orElseThrow();
        saved.delete();
        productJpaRepository.save(saved);
    }

    private long stockOf(Product product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private long balanceOf(User target) {
        return userJpaRepository.findById(target.getId()).orElseThrow().getBalance();
    }

    @DisplayName("POST /api/v1/orders")
    @Nested
    class Create {

        @DisplayName("여러 품목으로 주문하면, DRAFT 주문과 품목·합계를 반환하고 재고·잔액은 차감하지 않는다. (ORD-001)")
        @Test
        void createsDraftOrder_withoutDeductingStockOrPoints() {
            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> response = createOrder(user, bagTwoAndCapOne());

            // assert
            OrderDto.OrderResponse data = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(data.orderId()).isNotNull(),
                () -> assertThat(data.status()).isEqualTo("DRAFT"),
                () -> assertThat(data.items())
                    .extracting(OrderDto.ItemResponse::productId, OrderDto.ItemResponse::productName, OrderDto.ItemResponse::quantity,
                        OrderDto.ItemResponse::unitPrice, OrderDto.ItemResponse::amount)
                    .containsExactlyInAnyOrder(
                        tuple(bag.getId(), "가방", 2L, 3_000L, 6_000L),
                        tuple(cap.getId(), "모자", 1L, 1_000L, 1_000L)
                    ),
                () -> assertThat(data.totalAmount()).isEqualTo(7_000L),
                () -> assertThat(data.paidAmount()).isNull(),
                () -> assertThat(data.confirmedAt()).isNull(),
                () -> assertThat(stockOf(bag)).isEqualTo(5L),
                () -> assertThat(balanceOf(user)).isZero()
            );
        }

        @DisplayName("같은 상품 품목이 여러 줄이면, 수량을 합쳐 한 품목으로 저장한다. (T-3)")
        @Test
        void mergesSameProductItems() {
            // arrange
            OrderDto.CreateRequest request = new OrderDto.CreateRequest(List.of(
                new OrderDto.ItemRequest(bag.getId(), 3L),
                new OrderDto.ItemRequest(bag.getId(), 3L)
            ));

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> response = createOrder(user, request);

            // assert
            OrderDto.OrderResponse data = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(data.items()).singleElement()
                    .satisfies(item -> assertThat(item.quantity()).isEqualTo(6L)),
                () -> assertThat(data.totalAmount()).isEqualTo(18_000L)
            );
        }

        @DisplayName("품목이 비어 있거나 수량이 0이면, 400 BAD_REQUEST 응답을 받고 주문이 저장되지 않는다. (ORD-002)")
        @Test
        void returnsBadRequest_whenItemsAreEmptyOrQuantityIsZero() {
            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> empty = createOrder(user, new OrderDto.CreateRequest(List.of()));
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> zeroQuantity = createOrder(user,
                new OrderDto.CreateRequest(List.of(new OrderDto.ItemRequest(bag.getId(), 0L))));

            // assert
            assertAll(
                () -> assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(zeroQuantity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(orderJpaRepository.count()).isZero()
            );
        }

        @DisplayName("품목 목록·상품 ID·수량이 누락되면, 400 BAD_REQUEST 응답을 받고 주문이 저장되지 않는다.")
        @Test
        void returnsBadRequest_whenRequiredItemFieldIsMissing() {
            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> missingItems = createOrder(user, new OrderDto.CreateRequest(null));
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> missingProductId = createOrder(user,
                new OrderDto.CreateRequest(List.of(new OrderDto.ItemRequest(null, 1L))));
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> missingQuantity = createOrder(user,
                new OrderDto.CreateRequest(List.of(new OrderDto.ItemRequest(bag.getId(), null))));

            // assert
            assertAll(
                () -> assertThat(missingItems.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(missingProductId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(missingQuantity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(orderJpaRepository.count()).isZero()
            );
        }

        @DisplayName("존재하지 않거나 삭제된 상품을 주문하면, 404 NOT_FOUND 응답을 받고 주문이 저장되지 않는다. (ORD-002)")
        @Test
        void returnsNotFound_whenProductDoesNotExistOrIsDeleted() {
            // arrange
            deleteProduct(cap);

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> notExists = createOrder(user,
                new OrderDto.CreateRequest(List.of(new OrderDto.ItemRequest(999999L, 1L))));
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> deleted = createOrder(user, bagTwoAndCapOne());

            // assert
            assertAll(
                () -> assertThat(notExists.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(orderJpaRepository.count()).isZero()
            );
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm")
    @Nested
    class Confirm {

        @DisplayName("잔액 10,000에서 합계 7,000 주문을 확정하면, CONFIRMED·결제액 7,000·잔액 3,000이 되고 재고가 줄어든다. (ORD-005)")
        @Test
        void confirmsOrder_andDeductsStockAndPoints() {
            // arrange
            chargedUser(user, 10_000L);
            Long orderId = createdOrderId(user, bagTwoAndCapOne());

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> response = confirmOrder(user, orderId);

            // assert
            OrderDto.OrderResponse data = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(data.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(data.totalAmount()).isEqualTo(7_000L),
                () -> assertThat(data.paidAmount()).isEqualTo(7_000L),
                () -> assertThat(data.confirmedAt()).isNotNull(),
                () -> assertThat(balanceOf(user)).isEqualTo(3_000L),
                () -> assertThat(stockOf(bag)).isEqualTo(3L),
                () -> assertThat(stockOf(cap)).isEqualTo(4L)
            );
        }

        @DisplayName("주문 생성 뒤 상품 가격이 바뀌었으면, 확정 시점 가격으로 합계·결제액을 계산한다. (T-2)")
        @Test
        void usesPriceAtConfirmation_whenPriceChangedAfterCreation() {
            // arrange
            chargedUser(user, 10_000L);
            Long orderId = createdOrderId(user, bagTwoAndCapOne());
            changePrice(bag, 4_000L);

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> response = confirmOrder(user, orderId);

            // assert
            OrderDto.OrderResponse data = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(data.totalAmount()).isEqualTo(9_000L),
                () -> assertThat(data.paidAmount()).isEqualTo(9_000L),
                () -> assertThat(balanceOf(user)).isEqualTo(1_000L)
            );
        }

        @DisplayName("중복 품목을 합산한 총수량이 재고보다 많으면, 409 CONFLICT 응답을 받고 재고·잔액·상태가 유지된다. (ORD-004, T-3)")
        @Test
        void returnsConflict_andKeepsState_whenStockIsNotEnough() {
            // arrange
            chargedUser(user, 100_000L);
            Long orderId = createdOrderId(user, new OrderDto.CreateRequest(List.of(
                new OrderDto.ItemRequest(cap.getId(), 1L),
                new OrderDto.ItemRequest(bag.getId(), 3L),
                new OrderDto.ItemRequest(bag.getId(), 3L)
            )));

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> response = confirmOrder(user, orderId);

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(stockOf(bag)).isEqualTo(5L),
                () -> assertThat(stockOf(cap)).isEqualTo(5L),
                () -> assertThat(balanceOf(user)).isEqualTo(100_000L),
                () -> assertThat(getOrder(user, orderId).getBody().data().status()).isEqualTo("DRAFT")
            );
        }

        @DisplayName("잔액이 합계보다 적으면, 409 CONFLICT 응답을 받고 재고·잔액·상태가 유지된다. (ORD-004, 결정 3)")
        @Test
        void returnsConflict_andRollsBackStock_whenPointsAreNotEnough() {
            // arrange
            chargedUser(user, 6_000L);
            Long orderId = createdOrderId(user, bagTwoAndCapOne());

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> response = confirmOrder(user, orderId);

            // assert
            OrderDto.OrderResponse order = getOrder(user, orderId).getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(stockOf(bag)).isEqualTo(5L),
                () -> assertThat(stockOf(cap)).isEqualTo(5L),
                () -> assertThat(balanceOf(user)).isEqualTo(6_000L),
                () -> assertThat(order.status()).isEqualTo("DRAFT"),
                () -> assertThat(order.paidAmount()).isNull()
            );
        }

        @DisplayName("이미 확정된 주문을 다시 확정하면, 409 CONFLICT 응답을 받고 재고·잔액이 다시 차감되지 않는다. (ORD-003)")
        @Test
        void returnsConflict_whenOrderIsAlreadyConfirmed() {
            // arrange
            chargedUser(user, 20_000L);
            Long orderId = createdOrderId(user, bagTwoAndCapOne());
            confirmOrder(user, orderId);

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> response = confirmOrder(user, orderId);

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(balanceOf(user)).isEqualTo(13_000L),
                () -> assertThat(stockOf(bag)).isEqualTo(3L)
            );
        }

        @DisplayName("주문 생성 뒤 품목의 상품이 삭제되면, 409 CONFLICT 응답을 받고 재고·잔액·상태가 유지된다. (ORD-002, P-10)")
        @Test
        void returnsConflict_whenProductIsDeletedAfterCreation() {
            // arrange
            chargedUser(user, 10_000L);
            Long orderId = createdOrderId(user, bagTwoAndCapOne());
            deleteProduct(cap);

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> response = confirmOrder(user, orderId);

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(stockOf(bag)).isEqualTo(5L),
                () -> assertThat(balanceOf(user)).isEqualTo(10_000L),
                () -> assertThat(getOrder(user, orderId).getBody().data().status()).isEqualTo("DRAFT")
            );
        }

        @DisplayName("다른 사용자의 주문을 확정하면, 없는 주문과 같은 404 NOT_FOUND 응답을 받고 차감되지 않는다. (ORD-003, P-10)")
        @Test
        void returnsNotFoundWithSameMessage_whenOrderBelongsToOtherUser() {
            // arrange
            User other = chargedUser(userJpaRepository.save(new User("user2")), 10_000L);
            chargedUser(user, 10_000L);
            Long orderId = createdOrderId(user, bagTwoAndCapOne());
            Long notExistingOrderId = orderId + 999_999L;

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> othersOrder = confirmOrder(other, orderId);
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> notExisting = confirmOrder(other, notExistingOrderId);

            // assert
            assertAll(
                () -> assertThat(othersOrder.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(notExisting.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(othersOrder.getBody().meta().message())
                    .isEqualTo(notExisting.getBody().meta().message().replace(String.valueOf(notExistingOrderId), String.valueOf(orderId))),
                () -> assertThat(balanceOf(other)).isEqualTo(10_000L),
                () -> assertThat(balanceOf(user)).isEqualTo(10_000L),
                () -> assertThat(stockOf(bag)).isEqualTo(5L)
            );
        }
    }

    @DisplayName("GET /api/v1/orders")
    @Nested
    class GetOrders {

        @DisplayName("주문 목록을 조회하면, 자신의 주문만 최신순으로 페이지 조회한다.")
        @Test
        void returnsOwnOrdersInLatestOrder() {
            // arrange
            User other = userJpaRepository.save(new User("user2"));
            Long first = createdOrderId(user, bagTwoAndCapOne());
            Long second = createdOrderId(user, new OrderDto.CreateRequest(List.of(new OrderDto.ItemRequest(cap.getId(), 1L))));
            createdOrderId(other, bagTwoAndCapOne());

            // act
            ResponseEntity<ApiResponse<PageResponse<OrderDto.OrderResponse>>> response = getOrders(user, "?page=0&size=20");

            // assert
            PageResponse<OrderDto.OrderResponse> page = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(page.content()).extracting(OrderDto.OrderResponse::orderId).containsExactly(second, first),
                () -> assertThat(page.totalElements()).isEqualTo(2L),
                () -> assertThat(page.content().get(1).items()).hasSize(2)
            );
        }

        @DisplayName("잘못된 page·size로 조회하면, 400 BAD_REQUEST 응답을 받는다.")
        @Test
        void returnsBadRequest_whenPageIsInvalid() {
            // act
            ResponseEntity<ApiResponse<PageResponse<OrderDto.OrderResponse>>> response = getOrders(user, "?page=-1&size=0");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @DisplayName("GET /api/v1/orders/{orderId}")
    @Nested
    class GetOrder {

        @DisplayName("확정 뒤 상품이 삭제되어도, 주문 상세의 품목·금액·결제액과 상품명이 그대로 조회된다. (DEL-003)")
        @Test
        void returnsStoredValues_whenProductIsDeletedAfterConfirmation() {
            // arrange
            chargedUser(user, 10_000L);
            Long orderId = createdOrderId(user, bagTwoAndCapOne());
            confirmOrder(user, orderId);
            deleteProduct(bag);

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> response = getOrder(user, orderId);

            // assert
            OrderDto.OrderResponse data = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(data.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(data.items())
                    .extracting(OrderDto.ItemResponse::productName, OrderDto.ItemResponse::quantity, OrderDto.ItemResponse::unitPrice)
                    .containsExactlyInAnyOrder(tuple("가방", 2L, 3_000L), tuple("모자", 1L, 1_000L)),
                () -> assertThat(data.paidAmount()).isEqualTo(7_000L)
            );
        }

        @DisplayName("다른 사용자의 주문을 조회하면, 404 NOT_FOUND 응답을 받는다. (P-10)")
        @Test
        void returnsNotFound_whenOrderBelongsToOtherUser() {
            // arrange
            User other = userJpaRepository.save(new User("user2"));
            Long orderId = createdOrderId(user, bagTwoAndCapOne());

            // act
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> response = getOrder(other, orderId);

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().data()).isNull()
            );
        }
    }

    @DisplayName("충전 → 여러 품목 주문 확정 → 내 주문·잔액 조회 흐름")
    @Nested
    class ChargeToConfirmFlow {

        @DisplayName("잔액 0에서 10,000원을 충전하고 7,000원 주문을 확정하면, 내 주문은 CONFIRMED이고 잔액은 3,000원이다.")
        @Test
        void leavesThreeThousandPoints_afterChargingAndConfirming() {
            // arrange
            HttpHeaders headers = headersOf(user);
            testRestTemplate.exchange("/api/v1/points/charge", HttpMethod.POST,
                new HttpEntity<>(new PointDto.ChargeRequest(10_000L), headers), new ParameterizedTypeReference<ApiResponse<PointDto.BalanceResponse>>() {});
            Long orderId = createdOrderId(user, bagTwoAndCapOne());

            // act
            confirmOrder(user, orderId);
            ResponseEntity<ApiResponse<PageResponse<OrderDto.OrderResponse>>> orders = getOrders(user, "");
            ResponseEntity<ApiResponse<PointDto.BalanceResponse>> balance = testRestTemplate.exchange("/api/v1/points", HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<ApiResponse<PointDto.BalanceResponse>>() {});

            // assert
            assertAll(
                () -> assertThat(orders.getBody().data().content()).singleElement()
                    .satisfies(order -> {
                        assertThat(order.status()).isEqualTo("CONFIRMED");
                        assertThat(order.paidAmount()).isEqualTo(7_000L);
                    }),
                () -> assertThat(balance.getBody().data().balance()).isEqualTo(3_000L)
            );
        }
    }
}
