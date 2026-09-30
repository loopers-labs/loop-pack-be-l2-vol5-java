package com.loopers.interfaces.api.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductV1ApiE2ETest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private BrandModel givenBrand() {
        return brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
    }

    private <T> ResponseEntity<ApiResponse<T>> get(String url, ParameterizedTypeReference<ApiResponse<T>> type) {
        return testRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null), type);
    }

    @DisplayName("GET /api/v1/products")
    @Nested
    class GetProducts {
        @DisplayName("정상 요청이면, 200과 브랜드 정보·좋아요 수가 포함된 목록을 반환한다.")
        @Test
        void returnsProductList_withBrandAndLikeCount() {
            // arrange
            BrandModel brand = givenBrand();
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 129_000L, brand.getId(), 10));
            likeJpaRepository.save(new LikeModel(1L, product.getId()));
            likeJpaRepository.save(new LikeModel(2L, product.getId()));

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductListResponse>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductListResponse>> response = get("/api/v1/products", type);

            // assert
            ProductV1Dto.ProductResponse item = response.getBody().data().products().get(0);
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(item.id()).isEqualTo(product.getId()),
                () -> assertThat(item.brand().id()).isEqualTo(brand.getId()),
                () -> assertThat(item.brand().name()).isEqualTo(brand.getName()),
                () -> assertThat(item.likeCount()).isEqualTo(2L)
            );
        }

        @DisplayName("brandId를 주면, 해당 브랜드의 상품만 반환한다.")
        @Test
        void filtersByBrandId() {
            // arrange
            BrandModel brandA = givenBrand();
            BrandModel brandB = brandJpaRepository.save(new BrandModel("아디다스", "스포츠 브랜드", "신발/의류"));
            ProductModel matched = productJpaRepository.save(new ProductModel("상품A", 1000L, brandA.getId(), 10));
            productJpaRepository.save(new ProductModel("상품B", 1000L, brandB.getId(), 10));

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductListResponse>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductListResponse>> response =
                get("/api/v1/products?brandId=" + brandA.getId(), type);

            // assert
            var products = response.getBody().data().products();
            assertAll(
                () -> assertThat(products).hasSize(1),
                () -> assertThat(products.get(0).id()).isEqualTo(matched.getId())
            );
        }

        @DisplayName("price_asc 정렬에서 가격이 같으면, 상품명 오름차순으로 동률이 깨진다.")
        @Test
        void breaksTieByName_whenSortIsPriceAscAndPricesAreEqual() {
            // arrange
            BrandModel brand = givenBrand();
            productJpaRepository.save(new ProductModel("다상품", 1000L, brand.getId(), 10));
            productJpaRepository.save(new ProductModel("가상품", 1000L, brand.getId(), 10));
            productJpaRepository.save(new ProductModel("나상품", 1000L, brand.getId(), 10));

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductListResponse>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductListResponse>> response =
                get("/api/v1/products?sort=price_asc", type);

            // assert
            assertThat(response.getBody().data().products())
                .extracting(ProductV1Dto.ProductResponse::name)
                .containsExactly("가상품", "나상품", "다상품");
        }

        @DisplayName("잘못된 sort 값을 주면, 400 BAD_REQUEST 응답을 받는다.")
        @Test
        void throwsBadRequest_whenSortValueIsInvalid() {
            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductListResponse>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductListResponse>> response =
                get("/api/v1/products?sort=invalid_sort", type);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("삭제된 상품은 목록에서 제외된다.")
        @Test
        void excludesDeletedProducts() {
            // arrange
            BrandModel brand = givenBrand();
            ProductModel active = productJpaRepository.save(new ProductModel("판매중", 1000L, brand.getId(), 10));
            ProductModel deleted = productJpaRepository.save(new ProductModel("단종", 1000L, brand.getId(), 10));
            deleted.delete();
            productJpaRepository.saveAndFlush(deleted);

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductListResponse>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductListResponse>> response = get("/api/v1/products", type);

            // assert
            assertThat(response.getBody().data().products())
                .extracting(ProductV1Dto.ProductResponse::id)
                .containsExactly(active.getId());
        }

        @DisplayName("page·size를 주면, 그 크기로 잘라서 반환하고 전체 개수를 함께 준다.")
        @Test
        void paginatesResults() {
            // arrange
            BrandModel brand = givenBrand();
            for (int i = 0; i < 5; i++) {
                productJpaRepository.save(new ProductModel("상품" + i, 1000L, brand.getId(), 10));
            }

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductListResponse>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductListResponse>> response =
                get("/api/v1/products?page=0&size=2", type);

            // assert
            ProductV1Dto.ProductListResponse body = response.getBody().data();
            assertAll(
                () -> assertThat(body.products()).hasSize(2),
                () -> assertThat(body.totalElements()).isEqualTo(5),
                () -> assertThat(body.totalPages()).isEqualTo(3)
            );
        }
    }

    @DisplayName("GET /api/v1/products/{productId}")
    @Nested
    class GetProduct {
        @DisplayName("존재하는 상품 ID를 주면, 브랜드 정보·좋아요 수가 포함된 상세를 반환한다.")
        @Test
        void returnsProductDetail_withBrandAndLikeCount() {
            // arrange
            BrandModel brand = givenBrand();
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 129_000L, brand.getId(), 10));
            likeJpaRepository.save(new LikeModel(1L, product.getId()));

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response =
                get("/api/v1/products/" + product.getId(), type);

            // assert
            ProductV1Dto.ProductResponse body = response.getBody().data();
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(body.brand().id()).isEqualTo(brand.getId()),
                () -> assertThat(body.likeCount()).isEqualTo(1L)
            );
        }

        @DisplayName("존재하지 않는 상품 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void throwsNotFound_whenProductDoesNotExist() {
            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response = get("/api/v1/products/999", type);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("삭제된 상품 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void throwsNotFound_whenProductIsDeleted() {
            // arrange
            BrandModel brand = givenBrand();
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 129_000L, brand.getId(), 10));
            product.delete();
            productJpaRepository.saveAndFlush(product);

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> type = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response =
                get("/api/v1/products/" + product.getId(), type);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
