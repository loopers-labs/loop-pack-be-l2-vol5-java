package com.loopers.interfaces.api.order;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserService;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {

    private static final String ORDERS_URL = "/api/v1/orders";
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> ORDER_RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderListResponse>> ORDER_LIST_RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final UserService userService;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public OrderV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        UserJpaRepository userJpaRepository,
        OrderJpaRepository orderJpaRepository,
        UserService userService,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.userService = userService;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ProductModel createProduct(long price, int stock) {
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
        return productJpaRepository.save(new ProductModel("에어맥스", price, brand.getId(), stock));
    }

    private Long createUser() {
        return userJpaRepository.save(new UserModel()).getId();
    }

    private Long createUserWithBalance(long balance) {
        Long userId = createUser();
        userService.chargePoint(userId, balance);
        return userId;
    }

    private HttpEntity<OrderV1Dto.CreateRequest> createRequest(Long userId, List<OrderV1Dto.CreateRequest.ItemRequest> items) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(new OrderV1Dto.CreateRequest(items), headers);
    }

    private HttpEntity<Void> requestWithUser(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(null, headers);
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> createOrder(Long userId, List<OrderV1Dto.CreateRequest.ItemRequest> items) {
        return testRestTemplate.exchange(ORDERS_URL, HttpMethod.POST, createRequest(userId, items), ORDER_RESPONSE_TYPE);
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmOrder(Long userId, Long orderId) {
        return testRestTemplate.exchange(
            ORDERS_URL + "/" + orderId + "/confirm", HttpMethod.POST, requestWithUser(userId), ORDER_RESPONSE_TYPE
        );
    }

    @DisplayName("POST /api/v1/orders")
    @Nested
    class Create {
        @DisplayName("유효한 품목으로 요청하면, 201과 DRAFT 주문을 반환한다.")
        @Test
        void returns201_whenItemsAreValid() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUser();
            var items = List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 2));

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(userId, items);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().data().status().name()).isEqualTo("DRAFT");
            assertThat(response.getBody().data().items()).hasSize(1);
        }

        @DisplayName("품목이 하나도 없으면, 400을 반환한다.")
        @Test
        void returns400_whenItemsAreEmpty() {
            // arrange
            Long userId = createUser();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(userId, List.of());

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("존재하지 않는 상품을 참조하면, 404를 반환한다.")
        @Test
        void returns404_whenProductDoesNotExist() {
            // arrange
            Long userId = createUser();
            var items = List.of(new OrderV1Dto.CreateRequest.ItemRequest(999L, 1));

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(userId, items);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("삭제된 상품을 참조하면, 404를 반환한다.")
        @Test
        void returns404_whenProductIsDeleted() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            product.delete();
            productJpaRepository.saveAndFlush(product);
            Long userId = createUser();
            var items = List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1));

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(userId, items);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("같은 상품이 여러 품목으로 들어오면, 수량을 합쳐 하나로 반환한다.")
        @Test
        void mergesItems_whenSameProductAppearsMultipleTimes() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUser();
            var items = List.of(
                new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 2),
                new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 3)
            );

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(userId, items);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().data().items()).hasSize(1);
            assertThat(response.getBody().data().items().get(0).quantity()).isEqualTo(5);
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm")
    @Nested
    class Confirm {
        @DisplayName("소유자가 DRAFT 주문을 확정하면, 200과 결제액을 반환하고 재고·포인트가 차감된다.")
        @Test
        void confirmsOrder_whenOwnerConfirmsDraftOrder() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUserWithBalance(5000L);
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created =
                createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 2)));
            Long orderId = created.getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().status().name()).isEqualTo("CONFIRMED");
            assertThat(response.getBody().data().paidAmount()).isEqualTo(2000L);
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getRemainingStock()).isEqualTo(8);
            assertThat(userJpaRepository.findById(userId).orElseThrow().getPoint().getBalance()).isEqualTo(3000L);
        }

        @DisplayName("소유자가 아니면, 404를 반환한다.")
        @Test
        void returns404_whenRequesterIsNotOwner() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long ownerId = createUserWithBalance(5000L);
            Long otherId = createUser();
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created =
                createOrder(ownerId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)));
            Long orderId = created.getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(otherId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("이미 확정된 주문이면, 409를 반환한다.")
        @Test
        void returns409_whenOrderIsAlreadyConfirmed() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUserWithBalance(5000L);
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created =
                createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)));
            Long orderId = created.getBody().data().id();
            confirmOrder(userId, orderId);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @DisplayName("재고가 부족하면, 400을 반환한다.")
        @Test
        void returns400_whenStockIsInsufficient() {
            // arrange
            ProductModel product = createProduct(1000L, 1);
            Long userId = createUserWithBalance(5000L);
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created =
                createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 2)));
            Long orderId = created.getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("포인트가 부족하면, 400을 반환하고 이미 차감한 재고도 롤백된다.")
        @Test
        void returns400_andRollsBackStock_whenPointsAreInsufficient() {
            // arrange
            ProductModel productA = createProduct(1000L, 5);
            ProductModel productB = createProduct(1000L, 5);
            Long userId = createUser(); // 잔액 0
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created = createOrder(userId, List.of(
                new OrderV1Dto.CreateRequest.ItemRequest(productA.getId(), 1),
                new OrderV1Dto.CreateRequest.ItemRequest(productB.getId(), 1)
            ));
            Long orderId = created.getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(productJpaRepository.findById(productA.getId()).orElseThrow().getRemainingStock()).isEqualTo(5);
            assertThat(productJpaRepository.findById(productB.getId()).orElseThrow().getRemainingStock()).isEqualTo(5);
        }

        @DisplayName("생성 이후 상품이 삭제됐으면, 404를 반환한다.")
        @Test
        void returns404_whenProductWasDeletedAfterCreation() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUserWithBalance(5000L);
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created =
                createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)));
            Long orderId = created.getBody().data().id();
            product.delete();
            productJpaRepository.saveAndFlush(product);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("재고 1개짜리 상품에 두 주문이 동시에 확정을 시도하면, 하나만 성공한다.")
        @Test
        void onlyOneSucceeds_whenTwoOrdersConfirmConcurrentlyWithStockOfOne() throws InterruptedException {
            // arrange
            ProductModel product = createProduct(1000L, 1);
            Long userA = createUserWithBalance(5000L);
            Long userB = createUserWithBalance(5000L);
            Long orderAId = createOrder(userA, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)))
                .getBody().data().id();
            Long orderBId = createOrder(userB, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)))
                .getBody().data().id();

            ExecutorService executorService = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failureCount = new AtomicInteger();

            // act
            executorService.submit(() -> {
                try {
                    if (confirmOrder(userA, orderAId).getStatusCode() == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
            executorService.submit(() -> {
                try {
                    if (confirmOrder(userB, orderBId).getStatusCode() == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
            latch.await(10, TimeUnit.SECONDS);
            executorService.shutdown();
            executorService.awaitTermination(10, TimeUnit.SECONDS);

            // assert
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(failureCount.get()).isEqualTo(1);
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getRemainingStock()).isEqualTo(0);
        }

        @DisplayName("같은 주문에 확정 요청이 동시에 두 번 겹치면(더블클릭), 하나만 성공하고 재고·포인트는 한 번만 차감된다.")
        @Test
        void onlyOneSucceeds_whenSameOrderIsConfirmedConcurrentlyTwice() throws InterruptedException {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUserWithBalance(5000L);
            Long orderId = createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 2)))
                .getBody().data().id();

            ExecutorService executorService = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger conflictCount = new AtomicInteger();

            // act
            Runnable confirmAttempt = () -> {
                try {
                    HttpStatusCode status = confirmOrder(userId, orderId).getStatusCode();
                    if (status == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    } else if (status == HttpStatus.CONFLICT) {
                        conflictCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            };
            executorService.submit(confirmAttempt);
            executorService.submit(confirmAttempt);
            latch.await(10, TimeUnit.SECONDS);
            executorService.shutdown();
            executorService.awaitTermination(10, TimeUnit.SECONDS);

            // assert
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(1);
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getRemainingStock()).isEqualTo(8);
            assertThat(userJpaRepository.findById(userId).orElseThrow().getPoint().getBalance()).isEqualTo(3000L);
        }
    }

    @DisplayName("GET /api/v1/orders")
    @Nested
    class GetMyOrders {
        @DisplayName("요청자의 주문 목록을 200으로 반환한다.")
        @Test
        void returns200_withMyOrders() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUser();
            createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)));

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderListResponse>> response =
                testRestTemplate.exchange(ORDERS_URL, HttpMethod.GET, requestWithUser(userId), ORDER_LIST_RESPONSE_TYPE);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().orders()).hasSize(1);
        }
    }

    @DisplayName("GET /api/v1/orders/{orderId}")
    @Nested
    class GetMyOrder {
        @DisplayName("소유자가 조회하면, 200과 주문 상세를 반환한다.")
        @Test
        void returns200_whenOwnerRequests() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUser();
            Long orderId = createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                testRestTemplate.exchange(ORDERS_URL + "/" + orderId, HttpMethod.GET, requestWithUser(userId), ORDER_RESPONSE_TYPE);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().id()).isEqualTo(orderId);
        }

        @DisplayName("소유자가 아니면, 404를 반환한다.")
        @Test
        void returns404_whenRequesterIsNotOwner() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long ownerId = createUser();
            Long otherId = createUser();
            Long orderId = createOrder(ownerId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                testRestTemplate.exchange(ORDERS_URL + "/" + orderId, HttpMethod.GET, requestWithUser(otherId), ORDER_RESPONSE_TYPE);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
