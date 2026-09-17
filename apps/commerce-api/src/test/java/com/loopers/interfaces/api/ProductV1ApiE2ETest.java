package com.loopers.interfaces.api;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.product.ProductV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductV1ApiE2ETest {

    private static final ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductsResponse>> LIST_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> DETAIL_TYPE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final LikeJpaRepository likeJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public ProductV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        LikeJpaRepository likeJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.likeJpaRepository = likeJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ResponseEntity<ApiResponse<ProductV1Dto.ProductsResponse>> getProducts(String query) {
        return testRestTemplate.exchange(
            "/api/v1/products" + query, HttpMethod.GET, new HttpEntity<>(null), LIST_TYPE
        );
    }

    private List<Long> itemIds(ResponseEntity<ApiResponse<ProductV1Dto.ProductsResponse>> response) {
        return response.getBody().data().items().stream().map(ProductV1Dto.ProductResponse::id).toList();
    }

    @DisplayName("GET /api/v1/products — 목록은 브랜드명·좋아요 수를 포함하고, 기본 정렬은 최신순이며 삭제 상품을 제외한다.")
    @Test
    void returnsProducts_withBrandNameAndLikeCount() {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel p1 = productJpaRepository.save(new ProductModel(brand.getId(), "상품1", 10_000L, 5));
        ProductModel p2 = productJpaRepository.save(new ProductModel(brand.getId(), "상품2", 20_000L, 5));
        ProductModel deleted = new ProductModel(brand.getId(), "삭제된 상품", 30_000L, 5);
        deleted.delete();
        productJpaRepository.save(deleted);
        likeJpaRepository.save(new LikeModel(1L, p1.getId()));
        likeJpaRepository.save(new LikeModel(2L, p1.getId()));

        // act
        ResponseEntity<ApiResponse<ProductV1Dto.ProductsResponse>> response = getProducts("");

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(itemIds(response)).containsExactly(p2.getId(), p1.getId()),
            () -> assertThat(response.getBody().data().items().get(1).brandName()).isEqualTo("브랜드A"),
            () -> assertThat(response.getBody().data().items().get(1).likeCount()).isEqualTo(2L),
            () -> assertThat(response.getBody().data().totalCount()).isEqualTo(2L)
        );
    }

    @DisplayName("price_asc 정렬은 가격 오름차순이고, 동률이면 최신 등록(id 내림차순)이 먼저다.")
    @Test
    void sortsByPriceAsc_withIdDescTieBreaker() {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel expensive = productJpaRepository.save(new ProductModel(brand.getId(), "비싼 상품", 30_000L, 5));
        ProductModel cheapOld = productJpaRepository.save(new ProductModel(brand.getId(), "싼 상품(먼저)", 10_000L, 5));
        ProductModel cheapNew = productJpaRepository.save(new ProductModel(brand.getId(), "싼 상품(나중)", 10_000L, 5));

        // act
        ResponseEntity<ApiResponse<ProductV1Dto.ProductsResponse>> response = getProducts("?sort=price_asc");

        // assert
        assertThat(itemIds(response)).containsExactly(cheapNew.getId(), cheapOld.getId(), expensive.getId());
    }

    @DisplayName("likes_desc 정렬은 좋아요 수 내림차순이다.")
    @Test
    void sortsByLikesDesc() {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel p1 = productJpaRepository.save(new ProductModel(brand.getId(), "상품1", 10_000L, 5));
        ProductModel p2 = productJpaRepository.save(new ProductModel(brand.getId(), "상품2", 20_000L, 5));
        ProductModel p3 = productJpaRepository.save(new ProductModel(brand.getId(), "상품3", 30_000L, 5));
        likeJpaRepository.save(new LikeModel(1L, p2.getId()));
        likeJpaRepository.save(new LikeModel(2L, p2.getId()));
        likeJpaRepository.save(new LikeModel(1L, p3.getId()));

        // act
        ResponseEntity<ApiResponse<ProductV1Dto.ProductsResponse>> response = getProducts("?sort=likes_desc");

        // assert
        assertThat(itemIds(response)).containsExactly(p2.getId(), p3.getId(), p1.getId());
    }

    @DisplayName("브랜드 필터는 해당 브랜드의 상품만 반환한다.")
    @Test
    void filtersByBrand() {
        // arrange
        BrandModel brandA = brandJpaRepository.save(new BrandModel("브랜드A"));
        BrandModel brandB = brandJpaRepository.save(new BrandModel("브랜드B"));
        ProductModel productA = productJpaRepository.save(new ProductModel(brandA.getId(), "A상품", 10_000L, 5));
        productJpaRepository.save(new ProductModel(brandB.getId(), "B상품", 20_000L, 5));

        // act
        ResponseEntity<ApiResponse<ProductV1Dto.ProductsResponse>> response =
            getProducts("?brandId=" + brandA.getId());

        // assert
        assertThat(itemIds(response)).containsExactly(productA.getId());
    }

    @DisplayName("지원하지 않는 정렬 값이면, 400 BAD_REQUEST로 응답한다.")
    @Test
    void returns400_whenSortValueIsInvalid() {
        // act
        ResponseEntity<ApiResponse<ProductV1Dto.ProductsResponse>> response = getProducts("?sort=hot");

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @DisplayName("GET /api/v1/products/{productId} — 상세를 반환하고, 삭제된 상품은 404다.")
    @Test
    void returnsProductDetail_and404ForDeleted() {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "상품1", 10_000L, 5));
        ProductModel deleted = new ProductModel(brand.getId(), "삭제된 상품", 30_000L, 5);
        deleted.delete();
        ProductModel savedDeleted = productJpaRepository.save(deleted);
        likeJpaRepository.save(new LikeModel(1L, product.getId()));

        // act
        ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response = testRestTemplate.exchange(
            "/api/v1/products/" + product.getId(), HttpMethod.GET, new HttpEntity<>(null), DETAIL_TYPE
        );
        ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> deletedResponse = testRestTemplate.exchange(
            "/api/v1/products/" + savedDeleted.getId(), HttpMethod.GET, new HttpEntity<>(null), DETAIL_TYPE
        );

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(response.getBody().data().brandName()).isEqualTo("브랜드A"),
            () -> assertThat(response.getBody().data().likeCount()).isEqualTo(1L),
            () -> assertThat(deletedResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
        );
    }
}
