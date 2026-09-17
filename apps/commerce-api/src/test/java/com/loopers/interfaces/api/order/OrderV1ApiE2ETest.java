package com.loopers.interfaces.api.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.domain.point.Point;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {
    @Autowired TestRestTemplate rest;
    @Autowired BrandJpaRepository brands;
    @Autowired ProductJpaRepository products;
    @Autowired OrderJpaRepository orders;
    @Autowired PointJpaRepository points;
    @Autowired DatabaseCleanUp cleanup;

    @AfterEach void tearDown() { cleanup.truncateAllTables(); }

    @Test
    void createsDraftOrderWithoutChangingStock() {
        Brand brand = brands.save(Brand.create("Nike"));
        Product product = products.save(Product.create(brand, "Air Max", 100L));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "1");
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
        Brand brand = brands.save(Brand.create("Nike"));
        Product product = products.save(Product.create(brand, "Air Max", 100L));
        product.changeStockTo(2L);
        products.save(product);
        points.save(Point.create(1L, new com.loopers.domain.point.PointBalance(200L)));
        Order order = orders.save(Order.create(1L, java.util.List.of(
            new com.loopers.domain.order.OrderItem(product.getId(), product.getName(), product.getPrice(), 2))));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "1");

        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = rest.exchange(
            "/api/v1/orders/" + order.getId() + "/confirm", org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(headers), new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().status()).isEqualTo("CONFIRMED");
        assertThat(response.getBody().data().paymentAmount()).isEqualTo(200L);
        assertThat(response.getBody().data().paymentResult()).isEqualTo("SUCCESS");
        assertThat(points.findByUserId(1L).orElseThrow().getBalance().amount()).isZero();
        assertThat(products.findById(product.getId()).orElseThrow().getStock().amount()).isZero();
    }

    @Test
    void returnsOnlyOrdersOwnedByRequester() {
        Order ownOrder = orders.save(Order.create(1L, List.of(new com.loopers.domain.order.OrderItem(1L, "Air Max", 100L, 1))));
        orders.save(Order.create(2L, List.of(new com.loopers.domain.order.OrderItem(2L, "Pegasus", 100L, 1))));

        ResponseEntity<ApiResponse<List<OrderV1Dto.OrderResponse>>> response = rest.exchange(
            "/api/v1/orders", org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers("1")), new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).extracting(OrderV1Dto.OrderResponse::id).containsExactly(ownOrder.getId());
    }

    @Test
    void rejectsOtherUsersOrderDetail() {
        Order order = orders.save(Order.create(2L, List.of(new com.loopers.domain.order.OrderItem(1L, "Air Max", 100L, 1))));

        ResponseEntity<ApiResponse<Object>> response = rest.exchange(
            "/api/v1/orders/" + order.getId(), org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers("1")), new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returnsAllOrdersForAdminWithPaymentResult() {
        Order draft = orders.save(Order.create(1L, List.of(new com.loopers.domain.order.OrderItem(1L, "Air Max", 100L, 1))));
        Order confirmed = Order.create(2L, List.of(new com.loopers.domain.order.OrderItem(2L, "Pegasus", 200L, 1)));
        confirmed.confirm(200L);
        confirmed = orders.save(confirmed);

        ResponseEntity<ApiResponse<List<OrderV1Dto.OrderResponse>>> listResponse = rest.exchange(
            "/api-admin/v1/orders", org.springframework.http.HttpMethod.GET,
            null, new ParameterizedTypeReference<>() {}
        );
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> detailResponse = rest.exchange(
            "/api-admin/v1/orders/" + confirmed.getId(), org.springframework.http.HttpMethod.GET,
            null, new ParameterizedTypeReference<>() {}
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody().data()).extracting(OrderV1Dto.OrderResponse::id)
            .containsExactlyInAnyOrder(draft.getId(), confirmed.getId());
        assertThat(detailResponse.getBody().data().userId()).isEqualTo(2L);
        assertThat(detailResponse.getBody().data().paymentAmount()).isEqualTo(200L);
        assertThat(detailResponse.getBody().data().paymentResult()).isEqualTo("SUCCESS");
    }

    private HttpHeaders headers(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", userId);
        return headers;
    }
}
