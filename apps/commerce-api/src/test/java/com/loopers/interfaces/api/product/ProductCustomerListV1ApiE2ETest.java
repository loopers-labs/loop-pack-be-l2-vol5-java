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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
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
    private LikeJpaRepository likeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    void sortsSamePriceByProductIdAscending() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product first = productRepository.save(Product.create(brand, "First", 10_000L));
        Product second = productRepository.save(Product.create(brand, "Second", 10_000L));

        ResponseEntity<ApiResponse<List<ProductCustomerV1Dto.ProductResponse>>> response = getProducts("?sort=price_asc");

        assertThat(response.getBody().data()).extracting(ProductCustomerV1Dto.ProductResponse::id)
            .containsExactly(first.getId(), second.getId());
    }

    @Test
    void sortsByLikeCountThenProductIdDescending() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product mostLiked = productRepository.save(Product.create(brand, "Most", 10_000L));
        Product olderTie = productRepository.save(Product.create(brand, "Older", 10_000L));
        Product newerTie = productRepository.save(Product.create(brand, "Newer", 10_000L));
        likeRepository.save(Like.create(1L, mostLiked.getId()));
        likeRepository.save(Like.create(2L, mostLiked.getId()));
        likeRepository.save(Like.create(1L, olderTie.getId()));
        likeRepository.save(Like.create(2L, newerTie.getId()));

        ResponseEntity<ApiResponse<List<ProductCustomerV1Dto.ProductResponse>>> response = getProducts("?sort=likes_desc");

        assertThat(response.getBody().data()).extracting(ProductCustomerV1Dto.ProductResponse::id)
            .containsExactly(mostLiked.getId(), newerTie.getId(), olderTie.getId());
    }

    @Test
    void sortsLatestProductFirst() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product older = productRepository.save(Product.create(brand, "Older", 10_000L));
        Product newer = productRepository.save(Product.create(brand, "Newer", 10_000L));

        ResponseEntity<ApiResponse<List<ProductCustomerV1Dto.ProductResponse>>> response = getProducts("?sort=latest");

        assertThat(response.getBody().data()).extracting(ProductCustomerV1Dto.ProductResponse::id)
            .containsExactly(newer.getId(), older.getId());
    }

    @Test
    void sortsSameCreatedAtByProductIdDescending() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product olderId = productRepository.save(Product.create(brand, "Older", 10_000L));
        Product newerId = productRepository.save(Product.create(brand, "Newer", 10_000L));
        Timestamp sameCreatedAt = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        jdbcTemplate.update(
            "UPDATE products SET created_at = ? WHERE id IN (?, ?)",
            sameCreatedAt,
            olderId.getId(),
            newerId.getId()
        );

        ResponseEntity<ApiResponse<List<ProductCustomerV1Dto.ProductResponse>>> response = getProducts("?sort=latest");

        assertThat(response.getBody().data()).extracting(ProductCustomerV1Dto.ProductResponse::id)
            .containsExactly(newerId.getId(), olderId.getId());
    }

    @Test
    void rejectsInvalidSort() {
        ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
            "/api/v1/products?sort=unknown",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<ApiResponse<List<ProductCustomerV1Dto.ProductResponse>>> getProducts(String query) {
        return testRestTemplate.exchange(
            "/api/v1/products" + query,
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {}
        );
    }
}
