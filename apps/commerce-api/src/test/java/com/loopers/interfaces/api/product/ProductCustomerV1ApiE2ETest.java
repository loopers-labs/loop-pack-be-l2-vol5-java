package com.loopers.interfaces.api.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.Like;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductCustomerV1ApiE2ETest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandJpaRepository brandRepository;

    @Autowired
    private ProductJpaRepository productRepository;

    @Autowired
    private LikeJpaRepository likeRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void returnsCustomerProductDetail() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = productRepository.save(Product.create(brand, "Air Max", 100_000L));
        likeRepository.save(Like.create(1L, product.getId()));

        ParameterizedTypeReference<ApiResponse<ProductCustomerV1Dto.ProductResponse>> responseType =
            new ParameterizedTypeReference<>() {};
        ResponseEntity<ApiResponse<ProductCustomerV1Dto.ProductResponse>> response = testRestTemplate.exchange(
            "/api/v1/products/" + product.getId(),
            org.springframework.http.HttpMethod.GET,
            null,
            responseType
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().brandName()).isEqualTo("Nike");
        assertThat(response.getBody().data().likeCount()).isEqualTo(1L);
    }

    @Test
    void returnsNotFoundForDeletedProduct() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = productRepository.save(Product.create(brand, "Air Max", 100_000L));
        product.delete();
        productRepository.saveAndFlush(product);

        ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
            "/api/v1/products/" + product.getId(),
            org.springframework.http.HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
