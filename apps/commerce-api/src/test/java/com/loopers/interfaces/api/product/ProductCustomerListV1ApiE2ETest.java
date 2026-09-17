package com.loopers.interfaces.api.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductCustomerListV1ApiE2ETest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandJpaRepository brandRepository;

    @Autowired
    private ProductJpaRepository productRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void filtersByBrandAndSortsByPrice() {
        Brand nike = brandRepository.save(Brand.create("Nike"));
        Brand adidas = brandRepository.save(Brand.create("Adidas"));
        Product expensive = productRepository.save(Product.create(nike, "Expensive", 20_000L));
        Product cheap = productRepository.save(Product.create(nike, "Cheap", 10_000L));
        productRepository.save(Product.create(adidas, "Other", 1_000L));

        ResponseEntity<ApiResponse<List<ProductCustomerV1Dto.ProductResponse>>> response = testRestTemplate.exchange(
            "/api/v1/products?brandId=" + nike.getId() + "&sort=price_asc",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).extracting(ProductCustomerV1Dto.ProductResponse::id)
            .containsExactly(cheap.getId(), expensive.getId());
    }

    @Test
    void rejectsInvalidPage() {
        ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
            "/api/v1/products?page=-1",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
