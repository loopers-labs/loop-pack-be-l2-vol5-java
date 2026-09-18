package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.order.OrderJpaEntity;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.interfaces.api.order.OrderDto;
import com.loopers.interfaces.api.point.ChargePointController.ChargeRequest;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiE2ETest {
    @Autowired
    private BrandRepository brands;

    @Autowired
    private ProductRepository products;

    @Autowired
    private PointBalanceRepository points;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MockMvc mvc;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DatabaseCleanUp cleanUp;

    @Autowired
    private OrderRepository orders;

    @MockitoSpyBean
    private OrderJpaRepository orderJpaRepository;

    @Test
    @DisplayName("주문 생성은 품목을 합산해 저장하고 재고·잔액을 유지한다")
    void createsDraftWithoutDeduction() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Product secondProduct = Product.create(brand.getId(), "secondProduct", 1_000);
        secondProduct.setStock(5);
        secondProduct = products.save(secondProduct);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        OrderDto.Create input = new OrderDto.Create(List.of(
            new OrderDto.ItemRequest(product.getId(), 2),
            new OrderDto.ItemRequest(product.getId(), 1),
            new OrderDto.ItemRequest(secondProduct.getId(), 1)
        ));
        var request = new HttpEntity<>(input, headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode data = response.getBody().requiredAt("/data");
        assertThat(data.required("status").asText()).isEqualTo("DRAFT");
        assertThat(data.has("paidAmount")).isFalse();
        assertThat(data.has("paymentResult")).isFalse();
        Order stored = orders.findById(data.required("orderId").longValue()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getItems()).hasSize(2);
        assertThat(stored.getItems().getFirst().getQuantity()).isEqualTo(3);
        assertThat(stored.getItems().getFirst().getUnitPrice()).isEqualTo(2_000);
        assertThat(stored.getTotalAmount()).isEqualTo(7_000);
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(products.findById(secondProduct.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("10,000원 충전부터 여러 품목 7,000원 결제·잔액 조회까지 연결된다")
    void chargeAndCheckoutJourney() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Product secondProduct = Product.create(brand.getId(), "secondProduct", 1_000);
        secondProduct.setStock(5);
        secondProduct = products.save(secondProduct);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var chargeRequest = new HttpEntity<>(new ChargeRequest(10_000L), headers);
        var orderRequest = new HttpEntity<>(new OrderDto.Create(List.of(
            new OrderDto.ItemRequest(product.getId(), 2),
            new OrderDto.ItemRequest(secondProduct.getId(), 3)
        )), headers);
        HttpEntity<Void> identifiedRequest = new HttpEntity<>(headers);

        // act
        var charged = rest.exchange("/api/v1/points/charge", HttpMethod.POST, chargeRequest, JsonNode.class);
        var created = rest.exchange("/api/v1/orders", HttpMethod.POST, orderRequest, JsonNode.class);
        long orderId = created.getBody().requiredAt("/data/orderId").longValue();
        var confirmed = rest.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            identifiedRequest, JsonNode.class);
        var detail = rest.exchange("/api/v1/orders/" + orderId, HttpMethod.GET, identifiedRequest, JsonNode.class);
        var list = rest.exchange("/api/v1/orders", HttpMethod.GET, identifiedRequest, JsonNode.class);
        var remaining = rest.exchange("/api/v1/points", HttpMethod.GET, identifiedRequest, JsonNode.class);

        // assert
        assertThat(charged.getStatusCode().value()).isEqualTo(200);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().requiredAt("/data/status").asText()).isEqualTo("DRAFT");
        assertThat(created.getBody().requiredAt("/data").has("paidAmount")).isFalse();
        assertThat(created.getBody().requiredAt("/data").has("paymentResult")).isFalse();
        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        assertThat(confirmed.getBody().requiredAt("/data/status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.getBody().requiredAt("/data/paidAmount").longValue()).isEqualTo(7_000);
        assertThat(confirmed.getBody().requiredAt("/data/paymentResult").asText()).isEqualTo("SUCCESS");
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getBody()).isEqualTo(confirmed.getBody());
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody().requiredAt("/data/items/0")).isEqualTo(confirmed.getBody().requiredAt("/data"));
        assertThat(remaining.getStatusCode().value()).isEqualTo(200);
        assertThat(remaining.getBody().requiredAt("/data/balance").longValue()).isEqualTo(3_000);
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(3);
        assertThat(products.findById(secondProduct.getId()).orElseThrow().getStock()).isEqualTo(2);
        assertThat(orders.findById(orderId).orElseThrow().getPaidAmount()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("현재 상품 가격이 바뀌어도 주문 생성 당시 단가로 결제한다")
    void paysSnapshotPrice() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        product.update("변경", 3_000);
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/orders/" + order.getId() + "/confirm", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().requiredAt("/data/paidAmount").longValue()).isEqualTo(4_000);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(6_000);
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(3);
    }

    @Test
    @DisplayName("확정 후 상품이 삭제되어도 같은 결제 결과를 반환하고 추가 차감하지 않는다")
    void reusesConfirmationAfterProductDeletion() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);
        var first = rest.exchange("/api/v1/orders/" + order.getId() + "/confirm", HttpMethod.POST,
            request, JsonNode.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        product = products.findById(product.getId()).orElseThrow();
        product.delete();
        products.save(product);

        // act
        var response = rest.exchange("/api/v1/orders/" + order.getId() + "/confirm", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(first.getBody());
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(3);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(6_000);
    }

    @Test
    @DisplayName("확정된 주문도 다른 사용자에게 결제 결과를 반환하지 않는다")
    void checksOwnerBeforeReusingConfirmation() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        order.confirm();
        orders.save(order);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "2");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/orders/" + order.getId() + "/confirm", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("확정 전 상품이 삭제되면 주문·재고·잔액을 유지한다")
    void rejectsDeletedProductBeforeFirstConfirmation() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        product.delete();
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/orders/" + order.getId() + "/confirm", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("두 번째 상품 재고가 부족하면 첫 번째 상품 차감도 롤백한다")
    void rollsBackFirstStockWhenSecondStockIsInsufficient() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Product secondProduct = Product.create(brand.getId(), "secondProduct", 1_000);
        secondProduct.setStock(1);
        secondProduct = products.save(secondProduct);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000),
            new Order.RequestedItem(secondProduct.getId(), 2, 1_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/orders/" + order.getId() + "/confirm", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
        assertThat(products.findById(secondProduct.getId()).orElseThrow().getStock()).isEqualTo(1);
    }

    @Test
    @DisplayName("잔액 부족은 상품 차감을 롤백하고 기존 잔액을 유지한다")
    void rollsBackStockWhenBalanceIsInsufficient() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(3_000);
        points.save(point);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/orders/" + order.getId() + "/confirm", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("INSUFFICIENT_POINTS");
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("잔액 행이 없으면 결제를 거절하고 행을 새로 만들지 않는다")
    void missingBalanceDoesNotPersistFailedPayment() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/orders/" + order.getId() + "/confirm", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("INSUFFICIENT_POINTS");
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1)).isEmpty();
    }

    @Test
    @DisplayName("타인 주문과 없는 주문의 상세 조회 거절 응답은 동일하다")
    void foreignAndMissingOrdersMatchForGet() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "2");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/orders/" + order.getId() + "", HttpMethod.GET, request, JsonNode.class);
        var missing = rest.exchange("/api/v1/orders/999999", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(response.getStatusCode()).isEqualTo(missing.getStatusCode());
        assertThat(response.getBody()).isEqualTo(missing.getBody());
        assertThat(response.getBody().has("data")).isFalse();
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("타인 주문과 없는 주문의 확정 거절 응답은 동일하다")
    void foreignAndMissingOrdersMatchForPost() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "2");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/orders/" + order.getId() + "/confirm", HttpMethod.POST,
            request, JsonNode.class);
        var missing = rest.exchange("/api/v1/orders/999999/confirm", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(response.getStatusCode()).isEqualTo(missing.getStatusCode());
        assertThat(response.getBody()).isEqualTo(missing.getBody());
        assertThat(response.getBody().has("data")).isFalse();
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("고객 목록에는 본인 주문만 반환하고 구매자 식별자를 노출하지 않는다")
    void listsOnlyOwnOrders() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        Order other = orders.save(Order.create(2, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("orderId")).extracting(JsonNode::longValue).containsExactly(order.getId());
        assertThat(items.required(0).has("userId")).isFalse();
    }

    @Test
    @DisplayName("관리자는 구매자별 주문 목록을 조회한다")
    void adminFiltersByBuyer() throws Exception {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        Order other = orders.save(Order.create(2, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        var request = get("/api-admin/v1/orders?userId=2")
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].orderId").value(other.getId()));
    }

    @Test
    @DisplayName("구매자에 해당하는 주문이 없으면 빈 배열을 반환한다")
    void adminReturnsEmptyListForUnknownBuyer() throws Exception {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        var request = get("/api-admin/v1/orders?userId=999")
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray())
            .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    @DisplayName("관리자 상세는 구매자·품목·결제 결과를 반환한다")
    void adminReadsPaymentDetail() throws Exception {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        order.confirm();
        orders.save(order);
        var request = get("/api-admin/v1/orders/" + order.getId())
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(1))
            .andExpect(jsonPath("$.data.items[0].quantity").value(1))
            .andExpect(jsonPath("$.data.paidAmount").value(2_000))
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paymentResult").value("SUCCESS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"items\":[]}", "{\"items\":null}", "{\"items\":[null]}",
        "{\"items\":[{\"productId\":PRODUCT,\"quantity\":0}]}", "{\"items\":[{\"productId\":PRODUCT,\"quantity\":null}]}",
        "{\"items\":[{\"productId\":PRODUCT,\"quantity\":3},{\"productId\":PRODUCT,\"quantity\":-1}]}",
        "{\"items\":[{\"productId\":PRODUCT,\"quantity\":2147483648}]}",
        "{\"items\":[{\"productId\":PRODUCT,\"quantity\":2147483647},{\"productId\":PRODUCT,\"quantity\":1}]}",
        "{\"items\":[{\"productId\":PRODUCT,\"quantity\":1.5}]}"})
    @DisplayName("잘못된 주문 입력은 주문과 품목을 저장하지 않는다")
    void invalidOrderDoesNotPersistPartialState(String invalidBody) {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        String body = invalidBody.replace("PRODUCT", product.getId().toString());
        var request = new HttpEntity<>(body, headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(entityManager.createQuery("select count(e) from OrderJpaEntity e", Long.class)
            .getSingleResult()).isZero();
        assertThat(entityManager.createQuery("select count(item.productId) from OrderJpaEntity o join o.items item", Long.class)
            .getSingleResult()).isZero();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("주문 금액이 long 범위를 넘으면 주문과 품목을 저장하지 않는다")
    void rejectsAmountOverflow() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", Long.MAX_VALUE);
        product.setStock(0);
        product = products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        OrderDto.Create input = new OrderDto.Create(List.of(new OrderDto.ItemRequest(product.getId(), 2)));
        var request = new HttpEntity<>(input, headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("ORDER_AMOUNT_OVERFLOW");
        assertThat(entityManager.createQuery("select count(e) from OrderJpaEntity e", Long.class)
            .getSingleResult()).isZero();
        assertThat(entityManager.createQuery("select count(item.productId) from OrderJpaEntity o join o.items item", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("삭제한 상품으로 주문을 생성할 수 없다")
    void rejectsDeletedProductAtCreation() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        product.delete();
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var input = new OrderDto.Create(List.of(new OrderDto.ItemRequest(product.getId(), 1)));
        var request = new HttpEntity<>(input, headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(entityManager.createQuery("select count(e) from OrderJpaEntity e", Long.class)
            .getSingleResult()).isZero();
        assertThat(entityManager.createQuery("select count(item.productId) from OrderJpaEntity o join o.items item", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("없는 상품으로 주문을 생성할 수 없다")
    void rejectsMissingProductAtCreation() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var input = new OrderDto.Create(List.of(new OrderDto.ItemRequest(999L, 1)));
        var request = new HttpEntity<>(input, headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(entityManager.createQuery("select count(e) from OrderJpaEntity e", Long.class)
            .getSingleResult()).isZero();
        assertThat(entityManager.createQuery("select count(item.productId) from OrderJpaEntity o join o.items item", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("주문 저장 중 DB 오류가 발생하면 모든 결제 변경을 롤백한다")
    void databaseFailureRollsBackPayment() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order = orders.save(Order.create(1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);
        AtomicReference<PersistenceException> databaseFailure = new AtomicReference<>();
        // 관리 중인 주문의 컬럼 길이를 넘겨 실제 DB 저장 오류를 발생시킨다.
        doAnswer(invocation -> {
            OrderJpaEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "paymentResult", "x".repeat(256));
            try {
                entityManager.flush();
            } catch (PersistenceException exception) {
                databaseFailure.set(exception);
                throw exception;
            }
            return entity;
        }).when(orderJpaRepository).save(any(OrderJpaEntity.class));

        // act
        var response = rest.exchange("/api/v1/orders/" + order.getId() + "/confirm", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(databaseFailure.get()).isNotNull().hasMessageContaining("payment_result");
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?userId=0"})
    @DisplayName("관리자 주문 목록의 잘못된 조회 조건을 거절한다")
    void rejectsInvalidAdminQuery(String query) throws Exception {
        // arrange
        var request = get("/api-admin/v1/orders" + query)
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest()).andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"));
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
