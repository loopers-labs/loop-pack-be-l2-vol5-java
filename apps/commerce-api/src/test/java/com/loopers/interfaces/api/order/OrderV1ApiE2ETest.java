package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.payment.ProviderOrderSuffixGenerator;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.PaymentResult;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.support.testpg.TestPgControl;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.UUID;
import static org.mockito.Mockito.when;
import com.loopers.interfaces.api.order.OrderV1Dto.OrderItemRequest;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.admin.AdminMockMvcClient;
import com.loopers.interfaces.api.admin.order.OrderAdminV1Dto;
import com.loopers.interfaces.api.point.PointV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class OrderV1ApiE2ETest {
    @Autowired TestRestTemplate rest;
    @Autowired BrandRepository brands;
    @Autowired ProductRepository products;
    @Autowired OrderRepository orders;
    @Autowired PointRepository points;
    @Autowired UserRepository users;
    @Autowired DatabaseCleanUp cleanup;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PaymentAttemptRepository paymentAttempts;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @MockitoBean ProviderOrderSuffixGenerator suffixGenerator;

    // 테스트 PG의 거래·시나리오는 테스트 사이에 남고 주문 ID는 다시 1부터 시작하므로 테스트마다 다른 PG 주문 식별자를 쓴다.
    private String suffix;

    private AdminMockMvcClient adminClient;

    @BeforeEach void setUp() {
        adminClient = new AdminMockMvcClient(mockMvc, objectMapper);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenAnswer(invocation -> suffix);
    }

    @AfterEach void tearDown() { cleanup.truncateAllTables(); }

    @Test
    void createsDraftOrderWithoutChangingStock() {
        User user = saveUser();
        Brand brand = brands.save(Brand.create("Nike"));
        Product product = products.save(Product.create(brand.getId(), "Air Max", 100L));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", user.getId().toString());
        OrderV1Dto.CreateRequest request = new OrderV1Dto.CreateRequest(
            java.util.List.of(new com.loopers.interfaces.api.order.OrderV1Dto.OrderItemRequest(product.getId(), 2))
        );

        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = rest.exchange(
            "/api/v1/orders", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(request, headers), new ParameterizedTypeReference<>() {}
        );

        Order saved = orders.findById(response.getBody().data().id()).orElseThrow();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getStatus().name()).isEqualTo("DRAFT");
        assertThat(saved.getTotalAmount()).isEqualTo(200L);
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsNullOrderItemWithoutSavingAnOrderOrChangingExistingData(boolean includeValidItem) {
        User user = saveUser();
        Brand brand = brands.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "Air Max", 100L);
        product.changeStockTo(5L);
        product = products.save(product);
        points.save(Point.create(user.getId(), new PointBalance(1_000L)));
        Order existingOrder = orders.save(Order.create(user.getId(), List.of(
            OrderItem.create(product.getId(), product.getName(), product.getPrice(), 1)
        )));
        List<OrderItemRequest> requestItems = new ArrayList<>();
        if (includeValidItem) {
            requestItems.add(new OrderItemRequest(product.getId(), 2));
        }
        requestItems.add(null);

        ResponseEntity<ApiResponse<Object>> response = rest.exchange(
            "/api/v1/orders", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(new OrderV1Dto.CreateRequest(requestItems), headers(user.getId().toString())),
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL);
        assertThat(orders.findAll()).singleElement().satisfies(savedOrder -> {
            assertThat(savedOrder.getId()).isEqualTo(existingOrder.getId());
            assertThat(savedOrder.getStatus()).isEqualTo(existingOrder.getStatus());
            assertThat(savedOrder.getTotalAmount()).isEqualTo(existingOrder.getTotalAmount());
            assertThat(savedOrder.getPaymentAmount()).isNull();
            assertThat(savedOrder.getPaymentResult()).isNull();
            assertThat(savedOrder.getItems()).containsExactlyElementsOf(existingOrder.getItems());
        });
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(5L);
        assertThat(points.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(1_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"quantity\":1}", "{\"productId\":null,\"quantity\":1}"})
    void rejectsMissingProductIdWithoutChangingExistingData(String invalidItem) {
        User user = saveUser();
        Brand brand = brands.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "Air Max", 100L);
        product.changeStockTo(5L);
        product = products.save(product);
        points.save(Point.create(user.getId(), new PointBalance(1_000L)));
        Order existingOrder = orders.save(Order.create(user.getId(), List.of(
            OrderItem.create(product.getId(), product.getName(), product.getPrice(), 1)
        )));
        HttpHeaders requestHeaders = headers(user.getId().toString());
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        String request = "{\"items\":[{\"productId\":" + product.getId()
            + ",\"quantity\":2}," + invalidItem + "]}";

        ResponseEntity<ApiResponse<Object>> response = rest.exchange(
            "/api/v1/orders", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(request, requestHeaders), new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL);
        assertThat(orders.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getId()).isEqualTo(existingOrder.getId());
            assertThat(saved.getStatus()).isEqualTo(existingOrder.getStatus());
            assertThat(saved.getTotalAmount()).isEqualTo(existingOrder.getTotalAmount());
            assertThat(saved.getItems()).containsExactlyElementsOf(existingOrder.getItems());
            assertThat(saved.getPaymentAmount()).isNull();
            assertThat(saved.getPaymentResult()).isNull();
        });
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(5L);
        assertThat(points.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(1_000L);
    }

    @Test
    void rejectsDuplicateQuantityOverflowWithoutSavingAnOrderOrChangingExistingData() {
        User user = saveUser();
        Brand brand = brands.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "Air Max", 100L);
        product.changeStockTo(5L);
        product = products.save(product);
        Long productId = product.getId();
        points.save(Point.create(user.getId(), new com.loopers.domain.point.PointBalance(1_000L)));
        Order existingOrder = orders.save(Order.create(user.getId(), List.of(
            OrderItem.create(product.getId(), product.getName(), product.getPrice(), 1)
        )));
        OrderV1Dto.CreateRequest request = new OrderV1Dto.CreateRequest(List.of(
            new com.loopers.interfaces.api.order.OrderV1Dto.OrderItemRequest(product.getId(), Integer.MAX_VALUE),
            new com.loopers.interfaces.api.order.OrderV1Dto.OrderItemRequest(product.getId(), Integer.MAX_VALUE),
            new com.loopers.interfaces.api.order.OrderV1Dto.OrderItemRequest(product.getId(), 3)
        ));

        ResponseEntity<ApiResponse<Object>> response = rest.exchange(
            "/api/v1/orders", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(request, headers(user.getId().toString())), new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().meta().message()).contains("합산 주문 수량");
        assertThat(orders.findAll()).extracting(Order::getId).containsExactly(existingOrder.getId());
        Order savedOrder = orders.findById(existingOrder.getId()).orElseThrow();
        assertThat(savedOrder.getStatus().name()).isEqualTo("DRAFT");
        assertThat(savedOrder.getTotalAmount()).isEqualTo(100L);
        assertThat(savedOrder.getPaymentAmount()).isNull();
        assertThat(savedOrder.getPaymentResult()).isNull();
        assertThat(savedOrder.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(productId);
            assertThat(item.getQuantity()).isEqualTo(1);
            assertThat(item.getUnitPrice()).isEqualTo(100L);
        });
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(5L);
        assertThat(points.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(1_000L);
    }

    @Test
    void rejectsOrderTotalOverflowWithoutSavingAnOrderOrChangingExistingData() {
        User user = saveUser();
        Brand brand = brands.save(Brand.create("Nike"));
        points.save(Point.create(user.getId(), new com.loopers.domain.point.PointBalance(1_000L)));
        List<Product> preparedProducts = new ArrayList<>();
        List<com.loopers.interfaces.api.order.OrderV1Dto.OrderItemRequest> requestItems = new ArrayList<>();
        for (int index = 0; index < 43; index++) {
            Product product = Product.create(brand.getId(), "Product " + index, 100_000_000L);
            product.changeStockTo(5L);
            product = products.save(product);
            preparedProducts.add(product);
            requestItems.add(new com.loopers.interfaces.api.order.OrderV1Dto.OrderItemRequest(
                product.getId(), Integer.MAX_VALUE
            ));
        }
        Product firstProduct = preparedProducts.getFirst();
        Order existingOrder = orders.save(Order.create(user.getId(), List.of(
            OrderItem.create(firstProduct.getId(), firstProduct.getName(), firstProduct.getPrice(), 1)
        )));

        ResponseEntity<ApiResponse<Object>> response = rest.exchange(
            "/api/v1/orders", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(new OrderV1Dto.CreateRequest(requestItems), headers(user.getId().toString())),
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().meta().message()).contains("주문 총액");
        assertThat(orders.findAll()).extracting(Order::getId).containsExactly(existingOrder.getId());
        Order savedOrder = orders.findById(existingOrder.getId()).orElseThrow();
        assertThat(savedOrder.getStatus().name()).isEqualTo("DRAFT");
        assertThat(savedOrder.getTotalAmount()).isEqualTo(100_000_000L);
        assertThat(savedOrder.getPaymentAmount()).isNull();
        assertThat(savedOrder.getPaymentResult()).isNull();
        assertThat(savedOrder.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(firstProduct.getId());
            assertThat(item.getQuantity()).isEqualTo(1);
            assertThat(item.getUnitPrice()).isEqualTo(100_000_000L);
        });
        assertThat(preparedProducts).allSatisfy(product ->
            assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(5L)
        );
        assertThat(points.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(1_000L);
    }

    @DisplayName("결제를 요청하면 202와 결제 상태 객체를 응답하고, 재고는 차감하며 포인트는 쓰지 않는다.")
    @Test
    void requestsPaymentAndAnswersPaymentStatus() throws Exception {
        User user = saveUser();
        points.save(Point.create(user.getId(), new PointBalance(1_000L)));
        Product product = saveProduct(100L, 5L);
        Order order = saveDraft(user, product, 2);

        ResponseEntity<String> response = confirm(user, order.getId(), confirmBody(UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("orderId").asLong()).isEqualTo(order.getId());
        assertThat(data.path("orderStatus").asText()).isEqualTo("PAYMENT_PENDING");
        assertThat(data.path("paymentMethod").asText()).isEqualTo("PG");
        assertThat(data.path("paymentProgress").asText()).isEqualTo("WAITING");
        assertThat(data.path("attemptStatus").asText()).isEqualTo("PENDING");
        assertThat(data.has("paymentResult")).isTrue();
        assertThat(data.path("paymentResult").isNull()).isTrue();
        assertThat(data.has("paymentAmount")).isTrue();
        assertThat(data.path("paymentAmount").isNull()).isTrue();
        assertThat(data.path("links").path("order").asText()).isEqualTo("/api/v1/orders/" + order.getId());
        assertThat(data.path("links").path("reconcile").asText())
            .isEqualTo("/api/v1/orders/" + order.getId() + "/payment/reconcile");
        assertThat(data.has("transactionKey")).isFalse();
        assertThat(data.has("conflictReason")).isFalse();
        assertThat(data.has("paymentAttemptKey")).isFalse();
        assertThat(data.has("lookupUnavailable")).isFalse();
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(3L);
        assertThat(points.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(1_000L);
    }

    @DisplayName("PG가 생성 전에 거절하면 200과 종결된 결제 상태 객체를 응답하고 재확인 경로는 넣지 않는다.")
    @Test
    void answersTerminalStatusWithOk() throws Exception {
        User user = saveUser();
        Product product = saveProduct(100L, 5L);
        Order order = saveDraft(user, product, 2);
        TestPgControl.scenario("LP-ORD-%010d-%s".formatted(order.getId(), suffix), "{\"scenario\":\"REJECT_BEFORE_CREATE\"}");

        ResponseEntity<String> response = confirm(user, order.getId(), confirmBody(UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("orderStatus").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(data.path("paymentProgress").asText()).isEqualTo("FAILED");
        assertThat(data.path("attemptStatus").asText()).isEqualTo("FAILED");
        assertThat(data.path("paymentResult").asText()).isEqualTo("FAILED");
        assertThat(data.path("links").has("reconcile")).isFalse();
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(5L);
    }

    @DisplayName("타인 주문의 confirm은 404이고 소유자의 주문·재고·포인트·시도를 유지하며 PG를 호출하지 않는다.")
    @Test
    void rejectsConfirmForOtherUsersOrderWithoutEffects() {
        User owner = saveUser();
        User other = saveUser();
        points.save(Point.create(owner.getId(), new PointBalance(1_000L)));
        Product product = saveProduct(100L, 5L);
        Order order = saveDraft(owner, product, 2);

        ResponseEntity<String> response = confirm(other, order.getId(), confirmBody(UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertUntouchedDraft(owner, order, product);
    }

    @DisplayName("다른 주문에서 사용한 키의 confirm은 409이고 두 주문의 저장 상태를 유지하며 새 PG 생성을 하지 않는다.")
    @Test
    void rejectsAttemptKeyUsedForOtherOrderWithoutEffects() throws Exception {
        User user = saveUser();
        points.save(Point.create(user.getId(), new PointBalance(1_000L)));
        Product firstProduct = saveProduct(100L, 5L);
        Product secondProduct = saveProduct(100L, 5L);
        Order first = saveDraft(user, firstProduct, 2);
        Order second = saveDraft(user, secondProduct, 2);
        String key = UUID.randomUUID().toString();
        assertThat(confirm(user, first.getId(), confirmBody(key)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode firstBefore = detail(user, first.getId());
        TestPgControl.Stats firstStats = TestPgControl.stats(providerOrderId(first));

        ResponseEntity<String> response = confirm(user, second.getId(), confirmBody(key));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertUntouchedDraft(user, second, secondProduct);
        assertThat(detail(user, first.getId())).isEqualTo(firstBefore);
        assertThat(products.findById(firstProduct.getId()).orElseThrow().getStock().amount()).isEqualTo(3L);
        assertThat(TestPgControl.stats(providerOrderId(first))).isEqualTo(firstStats);
    }

    @DisplayName("진행 중인 주문에 새 키로 confirm하면 409이고 기존 시도·주문·재고를 유지하며 PG를 다시 호출하지 않는다.")
    @Test
    void rejectsNewAttemptKeyForStartedOrderWithoutEffects() throws Exception {
        User user = saveUser();
        points.save(Point.create(user.getId(), new PointBalance(1_000L)));
        Product product = saveProduct(100L, 5L);
        Order order = saveDraft(user, product, 2);
        assertThat(confirm(user, order.getId(), confirmBody(UUID.randomUUID().toString())).getStatusCode())
            .isEqualTo(HttpStatus.ACCEPTED);
        JsonNode before = detail(user, order.getId());
        Long attemptId = paymentAttempts.findByOrderId(order.getId()).orElseThrow().getId();
        TestPgControl.Stats stats = TestPgControl.stats(providerOrderId(order));

        ResponseEntity<String> response = confirm(user, order.getId(), confirmBody(UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(user, order.getId())).isEqualTo(before);
        assertThat(paymentAttempts.findByOrderId(order.getId()).orElseThrow().getId()).isEqualTo(attemptId);
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(3L);
        assertThat(points.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(1_000L);
        assertThat(TestPgControl.stats(providerOrderId(order))).isEqualTo(stats);
    }

    @DisplayName("PG 성공 주문을 같은 키로 confirm하면 200과 저장된 결과를 반환하고 PG·재고·포인트 효과를 추가하지 않는다.")
    @Test
    void replaysConfirmedPgOrderWithoutEffects() throws Exception {
        User user = saveUser();
        points.save(Point.create(user.getId(), new PointBalance(1_000L)));
        Product product = saveProduct(100L, 5L);
        Order order = saveDraft(user, product, 2);
        String key = UUID.randomUUID().toString();
        assertThat(confirm(user, order.getId(), confirmBody(key)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        TestPgControl.complete(providerOrderId(order));
        ResponseEntity<String> reconciled = rest.exchange("/api/v1/orders/" + order.getId() + "/payment/reconcile",
            org.springframework.http.HttpMethod.POST, new HttpEntity<>(headers(user.getId().toString())), String.class);
        assertThat(reconciled.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode before = detail(user, order.getId());
        TestPgControl.Stats stats = TestPgControl.stats(providerOrderId(order));

        assertThat(stats.createRequests()).isEqualTo(1);
        assertThat(stats.transactions()).isEqualTo(1);

        ResponseEntity<String> response = confirm(user, order.getId(), confirmBody(key));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).path("data")).isEqualTo(before.path("payment"));
        assertThat(detail(user, order.getId())).isEqualTo(before);
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(3L);
        assertThat(points.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(1_000L);
        assertThat(TestPgControl.stats(providerOrderId(order))).isEqualTo(stats);
    }

    @DisplayName("이전 POINT 주문의 confirm은 409이고 결제수단·금액·품목·잔액을 유지하며 PG를 호출하지 않는다.")
    @Test
    void rejectsConfirmForLegacyPointOrderWithoutEffects() throws Exception {
        User user = saveUser();
        points.save(Point.create(user.getId(), new PointBalance(1_000L)));
        Product product = saveProduct(100L, 5L);
        Order order = orders.save(Order.reconstitute(null, user.getId(), OrderStatus.CONFIRMED, 200L, 200L,
            PaymentResult.SUCCESS, PaymentMethod.POINT, null, List.of(
                OrderItem.create(product.getId(), "구매 당시 상품명", 100L, 2))));
        JsonNode before = detail(user, order.getId());

        ResponseEntity<String> response = confirm(user, order.getId(), confirmBody(UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(user, order.getId())).isEqualTo(before);
        assertThat(paymentAttempts.findByOrderId(order.getId())).isEmpty();
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(5L);
        assertThat(points.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(1_000L);
        assertThat(TestPgControl.stats(providerOrderId(order)).createRequests()).isZero();
    }

    @DisplayName("시도 키·카드 입력이 없거나 문자열이 아니거나 본문이 없으면 400이고 주문·재고·시도를 바꾸지 않는다.")
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}",
        "{\"paymentAttemptKey\":\"not-a-uuid\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}",
        "{\"paymentAttemptKey\":\"8f14e45f-ceea-467a-9575-2c9b3e5a1d20\",\"cardType\":\"TEST\",\"cardNo\":1234}",
        "{\"paymentAttemptKey\":\"8f14e45f-ceea-467a-9575-2c9b3e5a1d20\",\"cardType\":true,\"cardNo\":\"0000\"}",
        "{\"paymentAttemptKey\":\"8f14e45f-ceea-467a-9575-2c9b3e5a1d20\",\"cardType\":\" \",\"cardNo\":\"0000\"}",
        ""
    })
    void rejectsInvalidConfirmInputWithoutChangingState(String body) {
        User user = saveUser();
        Product product = saveProduct(100L, 5L);
        Order order = saveDraft(user, product, 2);

        ResponseEntity<String> response = confirm(user, order.getId(), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus().name()).isEqualTo("DRAFT");
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(5L);
        assertThat(paymentAttempts.findByOrderId(order.getId())).isEmpty();
    }

    @DisplayName("재고가 부족하면 409이고 주문·재고·포인트를 유지하며 PG를 호출하지 않는다.")
    @Test
    void rejectsConfirmAndPreservesStateWhenStockIsInsufficient() {
        User user = saveUser();
        points.save(Point.create(user.getId(), new PointBalance(200L)));
        Product product = saveProduct(100L, 1L);
        Order order = saveDraft(user, product, 2);

        ResponseEntity<String> response = confirm(user, order.getId(), confirmBody(UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus().name()).isEqualTo("DRAFT");
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(1L);
        assertThat(points.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(200L);
        assertThat(paymentAttempts.findByOrderId(order.getId())).isEmpty();
        assertThat(TestPgControl.stats(providerOrderId(order)).createRequests()).isZero();
    }

    @Test
    void completesChargeOrderAndInquiryFlow() {
        User user = saveUser();
        points.save(Point.create(user.getId()));
        Brand brand = brands.save(Brand.create("Nike"));
        Product airMax = products.save(Product.create(brand.getId(), "Air Max", 2_000L));
        Product pegasus = products.save(Product.create(brand.getId(), "Pegasus", 1_000L));
        airMax.changeStockTo(2L);
        pegasus.changeStockTo(3L);
        products.save(airMax);
        products.save(pegasus);
        HttpHeaders headers = headers(user.getId().toString());

        ResponseEntity<ApiResponse<PointV1Dto.ChargeResponse>> chargeResponse = rest.exchange(
            "/api/v1/points/charge", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(new PointV1Dto.ChargeRequest(10_000L), headers),
            new ParameterizedTypeReference<>() {}
        );
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> createResponse = rest.exchange(
            "/api/v1/orders", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(new OrderV1Dto.CreateRequest(List.of(
                new com.loopers.interfaces.api.order.OrderV1Dto.OrderItemRequest(airMax.getId(), 2),
                new com.loopers.interfaces.api.order.OrderV1Dto.OrderItemRequest(pegasus.getId(), 3)
            )), headers),
            new ParameterizedTypeReference<>() {}
        );
        Long orderId = createResponse.getBody().data().id();
        ResponseEntity<ApiResponse<OrderV1Dto.PaymentStatusResponse>> confirmResponse = rest.exchange(
            "/api/v1/orders/" + orderId + "/confirm", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(new OrderV1Dto.ConfirmRequest(UUID.randomUUID().toString(), "TEST", "0000-0000-0000-0000"),
                headers),
            new ParameterizedTypeReference<>() {}
        );
        ResponseEntity<ApiResponse<List<OrderV1Dto.OrderResponse>>> orderListResponse = rest.exchange(
            "/api/v1/orders", org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers), new ParameterizedTypeReference<>() {}
        );
        ResponseEntity<ApiResponse<PointV1Dto.BalanceResponse>> balanceResponse = rest.exchange(
            "/api/v1/points", org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers), new ParameterizedTypeReference<>() {}
        );
        Order requestedOrder = orders.findById(orderId).orElseThrow();

        assertThat(chargeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(createResponse.getBody().data().status()).isEqualTo("DRAFT");
        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(confirmResponse.getBody().data().orderStatus()).isEqualTo("PAYMENT_PENDING");
        assertThat(requestedOrder.getStatus().name()).isEqualTo("PAYMENT_PENDING");
        assertThat(requestedOrder.getTotalAmount()).isEqualTo(7_000L);
        assertThat(requestedOrder.getPaymentAmount()).isNull();
        assertThat(requestedOrder.getItems()).extracting(OrderItem::getProductId)
            .containsExactly(airMax.getId(), pegasus.getId());
        assertThat(requestedOrder.getItems()).extracting(OrderItem::getQuantity).containsExactly(2, 3);
        assertThat(requestedOrder.getItems()).extracting(OrderItem::getUnitPrice)
            .containsExactly(2_000L, 1_000L);
        assertThat(paymentAttempts.findByOrderId(orderId).orElseThrow().getAmount()).isEqualTo(7_000L);
        assertThat(orderListResponse.getBody().data()).extracting(OrderV1Dto.OrderResponse::id)
            .containsExactly(orderId);
        assertThat(balanceResponse.getBody().data().balance()).isEqualTo(10_000L);
        assertThat(products.findById(airMax.getId()).orElseThrow().getStock().amount()).isZero();
        assertThat(products.findById(pegasus.getId()).orElseThrow().getStock().amount()).isZero();
    }

    @DisplayName("주문 상세는 결제 상태 객체를 포함하고, 조회만으로 PG 결제를 만들지 않는다.")
    @Test
    void includesPaymentStatusInOrderDetailWithoutStartingPayment() throws Exception {
        User user = saveUser();
        Product product = saveProduct(100L, 5L);
        Order order = saveDraft(user, product, 2);

        JsonNode draft = detail(user, order.getId()).path("payment");

        assertThat(draft.path("orderId").asLong()).isEqualTo(order.getId());
        assertThat(draft.path("orderStatus").asText()).isEqualTo("DRAFT");
        assertThat(draft.has("paymentMethod")).isTrue();
        assertThat(draft.path("paymentMethod").isNull()).isTrue();
        assertThat(draft.path("paymentProgress").asText()).isEqualTo("NOT_STARTED");
        assertThat(draft.path("attemptStatus").isNull()).isTrue();
        assertThat(draft.path("links").path("order").asText()).isEqualTo("/api/v1/orders/" + order.getId());
        assertThat(draft.path("links").has("reconcile")).isFalse();
        assertThat(paymentAttempts.findByOrderId(order.getId())).isEmpty();

        confirm(user, order.getId(), confirmBody(UUID.randomUUID().toString()));
        JsonNode requested = detail(user, order.getId());
        JsonNode payment = requested.path("payment");

        assertThat(requested.path("status").asText()).isEqualTo("PAYMENT_PENDING");
        assertThat(requested.path("items")).hasSize(1);
        assertThat(payment.path("orderStatus").asText()).isEqualTo("PAYMENT_PENDING");
        assertThat(payment.path("paymentMethod").asText()).isEqualTo("PG");
        assertThat(payment.path("paymentProgress").asText()).isEqualTo("WAITING");
        assertThat(payment.path("attemptStatus").asText()).isEqualTo("PENDING");
        assertThat(payment.path("paymentResult").isNull()).isTrue();
        assertThat(payment.path("links").path("reconcile").asText())
            .isEqualTo("/api/v1/orders/" + order.getId() + "/payment/reconcile");
        assertThat(payment.has("transactionKey")).isFalse();
        assertThat(payment.has("conflictReason")).isFalse();
        assertThat(TestPgControl.stats(providerOrderId(order)).createRequests()).isEqualTo(1);
    }

    @DisplayName("이전 포인트 결제 주문은 결제수단 POINT·결제 완료·SUCCESS·당시 결제액으로 조회하고 품목을 그대로 둔다.")
    @Test
    void showsLegacyPointOrderAsCompleted() throws Exception {
        User user = saveUser();
        Order pointOrder = orders.save(Order.reconstitute(null, user.getId(), OrderStatus.CONFIRMED, 300L, 300L,
            PaymentResult.SUCCESS, PaymentMethod.POINT, null, List.of(OrderItem.create(7L, "Pegasus", 150L, 2))));

        JsonNode detail = detail(user, pointOrder.getId());
        JsonNode payment = detail.path("payment");

        assertThat(payment.path("paymentMethod").asText()).isEqualTo("POINT");
        assertThat(payment.path("paymentProgress").asText()).isEqualTo("COMPLETED");
        assertThat(payment.path("paymentResult").asText()).isEqualTo("SUCCESS");
        assertThat(payment.path("paymentAmount").asLong()).isEqualTo(300L);
        assertThat(payment.path("attemptStatus").isNull()).isTrue();
        assertThat(payment.path("links").has("reconcile")).isFalse();
        assertThat(detail.path("items").get(0).path("productName").asText()).isEqualTo("Pegasus");
        assertThat(detail.path("items").get(0).path("unitPrice").asLong()).isEqualTo(150L);
    }

    @DisplayName("내 주문 목록은 주문마다 결제 상태 객체를 포함한다.")
    @Test
    void includesPaymentStatusPerOrderInList() throws Exception {
        User user = saveUser();
        Order draft = saveDraft(user, saveProduct(100L, 5L), 1);
        Order requested = saveDraft(user, saveProduct(100L, 5L), 1);
        confirm(user, requested.getId(), confirmBody(UUID.randomUUID().toString()));

        JsonNode list = objectMapper.readTree(rest.exchange("/api/v1/orders", org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers(user.getId().toString())), String.class).getBody()).path("data");

        assertThat(list).hasSize(2);
        for (JsonNode order : list) {
            String expected = order.path("id").asLong() == draft.getId() ? "NOT_STARTED" : "WAITING";
            assertThat(order.path("payment").path("orderId").asLong()).isEqualTo(order.path("id").asLong());
            assertThat(order.path("payment").path("paymentProgress").asText()).isEqualTo(expected);
        }
    }

    @DisplayName("충돌이 기록된 결제 대기 주문은 고객에게 결과 미확인으로만 보이고, 관리자에게는 거래 키·충돌 사유를 보이되 고객 경로는 넣지 않는다.")
    @Test
    void showsConflictOnlyToAdmin() throws Exception {
        User user = saveUser();
        Order order = saveDraft(user, saveProduct(100L, 5L), 1);
        confirm(user, order.getId(), confirmBody(UUID.randomUUID().toString()));
        jdbcTemplate.update("UPDATE payment_attempts SET conflict_reason = 'TRANSACTION_MISMATCH', "
            + "conflict_detected_at = UTC_TIMESTAMP(6) WHERE order_id = ?", order.getId());
        String transactionKey = paymentAttempts.findByOrderId(order.getId()).orElseThrow().getTransactionKey();

        JsonNode customer = detail(user, order.getId()).path("payment");
        ResponseEntity<JsonNode> adminDetail = adminClient.exchange("/api-admin/v1/orders/" + order.getId(),
            org.springframework.http.HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        ResponseEntity<JsonNode> adminList = adminClient.exchange("/api-admin/v1/orders",
            org.springframework.http.HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(customer.path("paymentProgress").asText()).isEqualTo("UNCONFIRMED");
        assertThat(customer.has("conflictReason")).isFalse();
        assertThat(customer.has("transactionKey")).isFalse();
        JsonNode admin = adminDetail.getBody().path("data").path("payment");
        assertThat(admin.path("paymentProgress").asText()).isEqualTo("UNCONFIRMED");
        assertThat(admin.path("transactionKey").asText()).isEqualTo(transactionKey);
        assertThat(admin.path("conflictReason").asText()).isEqualTo("TRANSACTION_MISMATCH");
        assertThat(admin.has("links")).isFalse();
        assertThat(admin.has("paymentAttemptKey")).isFalse();
        assertThat(adminList.getBody().path("data").get(0).path("payment").path("conflictReason").asText())
            .isEqualTo("TRANSACTION_MISMATCH");
    }

    @Test
    void returnsOnlyOrdersOwnedByRequester() {
        User requester = saveUser();
        User otherUser = saveUser();
        Order ownOrder = orders.save(Order.create(requester.getId(), List.of(OrderItem.create(1L, "Air Max", 100L, 1))));
        orders.save(Order.create(otherUser.getId(), List.of(OrderItem.create(2L, "Pegasus", 100L, 1))));

        ResponseEntity<ApiResponse<List<OrderV1Dto.OrderResponse>>> response = rest.exchange(
            "/api/v1/orders", org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers(requester.getId().toString())), new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).extracting(OrderV1Dto.OrderResponse::id).containsExactly(ownOrder.getId());
    }

    @Test
    void rejectsOtherUsersOrderDetail() {
        User requester = saveUser();
        User orderOwner = saveUser();
        Order order = orders.save(Order.create(orderOwner.getId(), List.of(OrderItem.create(1L, "Air Max", 100L, 1))));

        ResponseEntity<ApiResponse<Object>> response = rest.exchange(
            "/api/v1/orders/" + order.getId(), org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers(requester.getId().toString())), new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returnsAllOrdersForAdminWithPaymentResult() {
        User draftOrderUser = saveUser();
        User confirmedOrderUser = saveUser();
        Order draft = orders.save(Order.create(draftOrderUser.getId(), List.of(OrderItem.create(1L, "Air Max", 100L, 1))));
        Order confirmed = orders.save(Order.reconstitute(null, confirmedOrderUser.getId(), OrderStatus.CONFIRMED, 200L, 200L,
            PaymentResult.SUCCESS, PaymentMethod.POINT, null, List.of(OrderItem.create(2L, "Pegasus", 200L, 1))));

        ResponseEntity<ApiResponse<List<OrderAdminV1Dto.OrderResponse>>> listResponse = adminClient.exchange(
            "/api-admin/v1/orders", org.springframework.http.HttpMethod.GET,
            null, new ParameterizedTypeReference<>() {}
        );
        ResponseEntity<ApiResponse<OrderAdminV1Dto.OrderResponse>> detailResponse = adminClient.exchange(
            "/api-admin/v1/orders/" + confirmed.getId(), org.springframework.http.HttpMethod.GET,
            null, new ParameterizedTypeReference<>() {}
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody().data()).extracting(OrderAdminV1Dto.OrderResponse::id)
            .containsExactlyInAnyOrder(draft.getId(), confirmed.getId());
        assertThat(detailResponse.getBody().data().userId()).isEqualTo(confirmedOrderUser.getId());
        assertThat(detailResponse.getBody().data().paymentAmount()).isEqualTo(200L);
        assertThat(detailResponse.getBody().data().paymentResult()).isEqualTo("SUCCESS");
    }

    private HttpHeaders headers(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", userId);
        return headers;
    }

    private User saveUser() {
        return users.save(User.create());
    }

    private Product saveProduct(long price, long stock) {
        Brand brand = brands.save(Brand.create("brand-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "Air Max", price);
        product.changeStockTo(stock);
        return products.save(product);
    }

    private Order saveDraft(User user, Product product, int quantity) {
        return orders.save(Order.create(user.getId(), List.of(
            OrderItem.create(product.getId(), product.getName(), product.getPrice(), quantity))));
    }

    private String confirmBody(String paymentAttemptKey) {
        return "{\"paymentAttemptKey\":\"" + paymentAttemptKey + "\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}";
    }

    private void assertUntouchedDraft(User user, Order order, Product product) {
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaymentMethod()).isNull();
        assertThat(stored.getPaymentAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(stored.getTotalAmount()).isEqualTo(order.getTotalAmount());
        assertThat(paymentAttempts.findByOrderId(order.getId())).isEmpty();
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isEqualTo(5L);
        assertThat(points.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(1_000L);
        assertThat(TestPgControl.stats(providerOrderId(order)).createRequests()).isZero();
    }

    private JsonNode detail(User user, Long orderId) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/orders/" + orderId, org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers(user.getId().toString())), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private String providerOrderId(Order order) {
        return "LP-ORD-%010d-%s".formatted(order.getId(), suffix);
    }

    private ResponseEntity<String> confirm(User user, Long orderId, String body) {
        HttpHeaders headers = headers(user.getId().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/v1/orders/" + orderId + "/confirm", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(body, headers), String.class);
    }
}
