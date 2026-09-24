package com.loopers.interfaces.api.mall.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.loopers.application.common.PageResult;
import com.loopers.application.mall.brand.BrandCommand;
import com.loopers.application.mall.brand.DeleteBrandUseCase;
import com.loopers.application.mall.product.AdminProduct;
import com.loopers.application.mall.product.ProductDetail;
import com.loopers.application.mall.product.ProductSummary;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.mall.brand.BrandApiDto;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiE2ETest {
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private DeleteBrandUseCase deleteBrandUseCase;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상품 관리와 조회")
    @Nested
    class ManageAndQueryProduct {
        @DisplayName("상품을 등록·수정·재고 설정·조회·삭제하고 저장 집계로 정렬한다")
        @Test
        void managesAndQueriesProducts() {
            long brandId = createBrand();
            AdminProduct expensive = createProduct(brandId, "비싼 상품", 2_000L, 5);
            AdminProduct popular = createProduct(brandId, "인기 상품", 1_000L, 0);
            saveLikeCount(expensive.productId(), 1L);
            saveLikeCount(popular.productId(), 5L);
            alignCreatedAt(expensive.productId(), popular.productId());

            ResponseEntity<ApiResponse<PageResult<ProductSummary>>> sorted = getProducts("likes_desc");
            ResponseEntity<ApiResponse<PageResult<ProductSummary>>> priceSorted = getProducts("price_asc");
            ResponseEntity<ApiResponse<PageResult<ProductSummary>>> latest = getProducts("latest");
            ResponseEntity<ApiResponse<PageResult<ProductSummary>>> secondPage = restTemplate.exchange(
                "/api/v1/products?sort=likes_desc&page=1&size=1",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {}
            );
            ResponseEntity<ApiResponse<ProductDetail>> detail = getProduct(expensive.productId());
            ResponseEntity<ApiResponse<AdminProduct>> updated = updateProduct(expensive.productId());
            ResponseEntity<ApiResponse<AdminProduct>> stocked = setStock(expensive.productId(), 0);
            ResponseEntity<ApiResponse<AdminProduct>> adminDetail = restTemplate.exchange(
                "/api-admin/v1/products/" + expensive.productId(),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {}
            );
            ResponseEntity<ApiResponse<PageResult<AdminProduct>>> adminList = restTemplate.exchange(
                "/api-admin/v1/products?brandId=" + brandId,
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {}
            );
            ResponseEntity<ApiResponse<Object>> deleted = deleteProduct(expensive.productId());
            ResponseEntity<ApiResponse<PageResult<ProductSummary>>> missingBrand = restTemplate.exchange(
                "/api/v1/products?brandId=999999",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {}
            );

            assertAll(
                () -> assertThat(sorted.getBody().data().items()).extracting(ProductSummary::productId)
                    .containsExactly(popular.productId(), expensive.productId()),
                () -> assertThat(priceSorted.getBody().data().items()).extracting(ProductSummary::productId)
                    .containsExactly(popular.productId(), expensive.productId()),
                () -> assertThat(latest.getBody().data().items()).extracting(ProductSummary::productId)
                    .containsExactly(popular.productId(), expensive.productId()),
                () -> assertThat(secondPage.getBody().data().items()).extracting(ProductSummary::productId)
                    .containsExactly(expensive.productId()),
                () -> assertThat(secondPage.getBody().data().totalElements()).isEqualTo(2L),
                () -> assertThat(secondPage.getBody().data().totalPages()).isEqualTo(2),
                () -> assertThat(detail.getBody().data().brand().brandId()).isEqualTo(brandId),
                () -> assertThat(detail.getBody().data().likeCount()).isEqualTo(1L),
                () -> assertThat(updated.getBody().data().name()).isEqualTo("변경 상품"),
                () -> assertThat(updated.getBody().data().stock()).isEqualTo(5),
                () -> assertThat(updated.getBody().data().likeCount()).isEqualTo(1L),
                () -> assertThat(stocked.getBody().data().stock()).isZero(),
                () -> assertThat(adminDetail.getBody().data().stock()).isZero(),
                () -> assertThat(adminList.getBody().data().items()).hasSize(2),
                () -> assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(getProduct(expensive.productId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(missingBrand.getBody().data().items()).isEmpty(),
                () -> assertThat(missingBrand.getBody().data().totalElements()).isZero()
            );
        }

        @DisplayName("브랜드 일괄 삭제 후 목록에서 제외되고 상세는 404를 반환한다")
        @Test
        void excludesFromListAndDetail_afterBrandBulkDelete() {
            long brandId = createBrand();
            AdminProduct product = createProduct(brandId, "상품", 1_000L, 5);

            deleteBrandUseCase.execute(new BrandCommand.Delete(brandId));

            ResponseEntity<ApiResponse<PageResult<ProductSummary>>> list = getProducts("latest");
            ResponseEntity<ApiResponse<ProductDetail>> detail = getProduct(product.productId());

            assertAll(
                () -> assertThat(list.getBody().data().items()).isEmpty(),
                () -> assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
            );
        }

        @DisplayName("잘못된 정렬·페이지·가격은 400이고 상품을 생성하지 않는다")
        @Test
        void rejectsInvalidInputs() {
            long brandId = createBrand();
            ResponseEntity<String> invalidSort = restTemplate.getForEntity("/api/v1/products?sort=unknown", String.class);
            ResponseEntity<String> invalidPage = restTemplate.getForEntity("/api/v1/products?page=-1", String.class);
            ResponseEntity<String> invalidProductId = restTemplate.getForEntity("/api/v1/products/0", String.class);
            ResponseEntity<String> invalidPrice = restTemplate.postForEntity(
                "/api-admin/v1/products",
                new ProductApiDto.CreateRequest(brandId, "상품", null, 0L, 0),
                String.class
            );

            assertAll(
                () -> assertThat(invalidSort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(invalidPage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(invalidProductId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(invalidPrice.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(jdbcClient.sql("SELECT COUNT(*) FROM products").query(Long.class).single()).isZero()
            );
        }
    }

    private long createBrand() {
        ResponseEntity<ApiResponse<BrandApiDto.Response>> response = restTemplate.exchange(
            "/api-admin/v1/brands",
            HttpMethod.POST,
            new HttpEntity<>(new BrandApiDto.Request("브랜드", null)),
            new ParameterizedTypeReference<>() {}
        );
        return response.getBody().data().brandId();
    }

    private AdminProduct createProduct(long brandId, String name, long price, int stock) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", "not-used");
        ResponseEntity<ApiResponse<AdminProduct>> response = restTemplate.exchange(
            "/api-admin/v1/products",
            HttpMethod.POST,
            new HttpEntity<>(new ProductApiDto.CreateRequest(brandId, name, null, price, stock), headers),
            new ParameterizedTypeReference<>() {}
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().data();
    }

    private void saveLikeCount(long productId, long count) {
        jdbcClient.sql("INSERT INTO product_like_counts (product_id, like_count) VALUES (:productId, :count)")
            .param("productId", productId)
            .param("count", count)
            .update();
    }

    private void alignCreatedAt(long firstProductId, long secondProductId) {
        jdbcClient.sql("""
                UPDATE products SET created_at = '2026-09-18 00:00:00'
                WHERE id IN (:firstProductId, :secondProductId)
                """)
            .param("firstProductId", firstProductId)
            .param("secondProductId", secondProductId)
            .update();
    }

    private ResponseEntity<ApiResponse<PageResult<ProductSummary>>> getProducts(String sort) {
        return restTemplate.exchange(
            "/api/v1/products?sort=" + sort,
            HttpMethod.GET,
            HttpEntity.EMPTY,
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<ProductDetail>> getProduct(long productId) {
        return restTemplate.exchange(
            "/api/v1/products/" + productId,
            HttpMethod.GET,
            HttpEntity.EMPTY,
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<AdminProduct>> updateProduct(long productId) {
        return restTemplate.exchange(
            "/api-admin/v1/products/" + productId,
            HttpMethod.PUT,
            new HttpEntity<>(new ProductApiDto.UpdateRequest("변경 상품", "변경 설명", 3_000L)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<AdminProduct>> setStock(long productId, int stock) {
        return restTemplate.exchange(
            "/api-admin/v1/products/" + productId + "/stock",
            HttpMethod.PUT,
            new HttpEntity<>(new ProductApiDto.StockRequest(stock)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<Object>> deleteProduct(long productId) {
        return restTemplate.exchange(
            "/api-admin/v1/products/" + productId,
            HttpMethod.DELETE,
            HttpEntity.EMPTY,
            new ParameterizedTypeReference<>() {}
        );
    }
}
