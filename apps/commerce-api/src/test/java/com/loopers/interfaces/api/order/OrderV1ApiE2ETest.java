package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.domain.point.Point;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.admin.AdminMockMvcClient;
import com.loopers.interfaces.api.point.PointV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class OrderV1ApiE2ETest {
    @Autowired TestRestTemplate rest;
    @Autowired BrandJpaRepository brands;
    @Autowired ProductJpaRepository products;
    @Autowired OrderJpaRepository orders;
    @Autowired PointJpaRepository points;
    @Autowired UserJpaRepository users;
    @Autowired DatabaseCleanUp cleanup;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private AdminMockMvcClient adminClient;

    @BeforeEach void setUp() { adminClient = new AdminMockMvcClient(mockMvc, objectMapper); }

    @AfterEach void tearDown() { cleanup.truncateAllTables(); }

    @Test
    void createsDraftOrderWithoutChangingStock() {
        User user = saveUser();
        Brand brand = brands.save(Brand.create("Nike"));
        Product product = products.save(Product.create(brand, "Air Max", 100L));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", user.getId().toString());
        OrderV1Dto.CreateRequest request = new OrderV1Dto.CreateRequest(
            java.util.List.of(new com.loopers.application.order.OrderFacade.OrderRequestItem(product.getId(), 2))
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

    @Test
    void confirmsDraftOrderAndChargesPointAndStock() {
        User user = saveUser();
        Brand brand = brands.save(Brand.create("Nike"));
        Product product = products.save(Product.create(brand, "Air Max", 100L));
        product.changeStockTo(2L);
        products.save(product);
        points.save(Point.create(user.getId(), new com.loopers.domain.point.PointBalance(200L)));
        Order order = orders.save(Order.create(user.getId(), java.util.List.of(
            new com.loopers.domain.order.OrderItem(product.getId(), product.getName(), product.getPrice(), 2))));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", user.getId().toString());

        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = rest.exchange(
            "/api/v1/orders/" + order.getId() + "/confirm", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(headers), new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().status()).isEqualTo("CONFIRMED");
        assertThat(response.getBody().data().paymentAmount()).isEqualTo(200L);
        assertThat(response.getBody().data().paymentResult()).isEqualTo("SUCCESS");
        assertThat(points.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isZero();
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isZero();
    }

    @Test
    void completesChargeOrderAndInquiryFlow() {
        User user = saveUser();
        points.save(Point.create(user.getId()));
        Brand brand = brands.save(Brand.create("Nike"));
        Product airMax = products.save(Product.create(brand, "Air Max", 2_000L));
        Product pegasus = products.save(Product.create(brand, "Pegasus", 1_000L));
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
                new com.loopers.application.order.OrderFacade.OrderRequestItem(airMax.getId(), 2),
                new com.loopers.application.order.OrderFacade.OrderRequestItem(pegasus.getId(), 3)
            )), headers),
            new ParameterizedTypeReference<>() {}
        );
        Long orderId = createResponse.getBody().data().id();
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmResponse = rest.exchange(
            "/api/v1/orders/" + orderId + "/confirm", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(headers), new ParameterizedTypeReference<>() {}
        );
        ResponseEntity<ApiResponse<List<OrderV1Dto.OrderResponse>>> orderListResponse = rest.exchange(
            "/api/v1/orders", org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers), new ParameterizedTypeReference<>() {}
        );
        ResponseEntity<ApiResponse<PointV1Dto.BalanceResponse>> balanceResponse = rest.exchange(
            "/api/v1/points", org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers), new ParameterizedTypeReference<>() {}
        );

        assertThat(chargeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(createResponse.getBody().data().status()).isEqualTo("DRAFT");
        assertThat(confirmResponse.getBody().data().status()).isEqualTo("CONFIRMED");
        assertThat(confirmResponse.getBody().data().paymentAmount()).isEqualTo(7_000L);
        assertThat(orderListResponse.getBody().data()).extracting(OrderV1Dto.OrderResponse::id)
            .containsExactly(orderId);
        assertThat(balanceResponse.getBody().data().balance()).isEqualTo(3_000L);
        assertThat(products.findById(airMax.getId()).orElseThrow().getStock().amount()).isZero();
        assertThat(products.findById(pegasus.getId()).orElseThrow().getStock().amount()).isZero();
    }

    @Test
    void returnsOnlyOrdersOwnedByRequester() {
        User requester = saveUser();
        User otherUser = saveUser();
        Order ownOrder = orders.save(Order.create(requester.getId(), List.of(new com.loopers.domain.order.OrderItem(1L, "Air Max", 100L, 1))));
        orders.save(Order.create(otherUser.getId(), List.of(new com.loopers.domain.order.OrderItem(2L, "Pegasus", 100L, 1))));

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
        Order order = orders.save(Order.create(orderOwner.getId(), List.of(new com.loopers.domain.order.OrderItem(1L, "Air Max", 100L, 1))));

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
        Order draft = orders.save(Order.create(draftOrderUser.getId(), List.of(new com.loopers.domain.order.OrderItem(1L, "Air Max", 100L, 1))));
        Order confirmed = Order.create(confirmedOrderUser.getId(), List.of(new com.loopers.domain.order.OrderItem(2L, "Pegasus", 200L, 1)));
        confirmed.confirm(200L);
        confirmed = orders.save(confirmed);

        ResponseEntity<ApiResponse<List<OrderV1Dto.OrderResponse>>> listResponse = adminClient.exchange(
            "/api-admin/v1/orders", org.springframework.http.HttpMethod.GET,
            null, new ParameterizedTypeReference<>() {}
        );
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> detailResponse = adminClient.exchange(
            "/api-admin/v1/orders/" + confirmed.getId(), org.springframework.http.HttpMethod.GET,
            null, new ParameterizedTypeReference<>() {}
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody().data()).extracting(OrderV1Dto.OrderResponse::id)
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
}
