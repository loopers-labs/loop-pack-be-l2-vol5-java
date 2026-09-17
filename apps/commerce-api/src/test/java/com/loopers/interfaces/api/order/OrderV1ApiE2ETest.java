package com.loopers.interfaces.api.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {
    @Autowired TestRestTemplate rest;
    @Autowired BrandJpaRepository brands;
    @Autowired ProductJpaRepository products;
    @Autowired OrderJpaRepository orders;
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
}
