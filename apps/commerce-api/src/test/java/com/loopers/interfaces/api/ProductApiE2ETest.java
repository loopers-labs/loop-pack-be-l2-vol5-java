package com.loopers.interfaces.api;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.Like;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.product.ProductDto;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiE2ETest {

    private static final String ENDPOINT_PRODUCTS = "/api/v1/products";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final LikeJpaRepository likeJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    ProductApiE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        UserJpaRepository userJpaRepository,
        LikeJpaRepository likeJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.likeJpaRepository = likeJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Brand saveBrand(String name) {
        return brandJpaRepository.save(new Brand(name));
    }

    private Product saveProduct(Brand brand, String name, long price) {
        return productJpaRepository.save(new Product(brand.getId(), name, price, 10L));
    }

    private void deleteProduct(Product product) {
        product.delete();
        productJpaRepository.save(product);
    }

    private void like(Product product, int count) {
        for (int i = 0; i < count; i++) {
            User user = userJpaRepository.save(new User("user-" + product.getId() + "-" + i));
            likeJpaRepository.save(new Like(user.getId(), product.getId()));
        }
    }

    private ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> getProducts(String query) {
        ParameterizedTypeReference<ApiResponse<PageResponse<ProductDto.ProductResponse>>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(ENDPOINT_PRODUCTS + query, HttpMethod.GET, new HttpEntity<>(null), responseType);
    }

    private ResponseEntity<ApiResponse<ProductDto.ProductResponse>> getProduct(String productId) {
        ParameterizedTypeReference<ApiResponse<ProductDto.ProductResponse>> responseType = new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(ENDPOINT_PRODUCTS + "/" + productId, HttpMethod.GET, new HttpEntity<>(null), responseType);
    }

    private List<Long> productIds(ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response) {
        return response.getBody().data().content().stream()
            .map(ProductDto.ProductResponse::productId)
            .toList();
    }

    @DisplayName("GET /api/v1/products")
    @Nested
    class GetProducts {

        @DisplayName("정렬을 지정하지 않으면, 최신 등록순으로 삭제되지 않은 상품만 반환하고 페이지 정보를 함께 준다. (DEL-002)")
        @Test
        void returnsLatestProductsExcludingDeleted_whenNoSortIsGiven() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Product first = saveProduct(brand, "가방", 30_000L);
            Product second = saveProduct(brand, "나시", 10_000L);
            Product deleted = saveProduct(brand, "다운", 20_000L);
            deleteProduct(deleted);

            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response = getProducts("");

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(productIds(response)).containsExactly(second.getId(), first.getId()),
                () -> assertThat(response.getBody().data().page()).isEqualTo(0),
                () -> assertThat(response.getBody().data().size()).isEqualTo(20),
                () -> assertThat(response.getBody().data().totalElements()).isEqualTo(2L),
                () -> assertThat(response.getBody().data().totalPages()).isEqualTo(1)
            );
        }

        @DisplayName("각 상품에 브랜드 정보와 좋아요 수를 포함한다. 좋아요가 없으면 0이다. (LIK-002)")
        @Test
        void returnsBrandAndLikeCount_forEachProduct() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Product liked = saveProduct(brand, "가방", 30_000L);
            Product notLiked = saveProduct(brand, "나시", 10_000L);
            like(liked, 2);

            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response = getProducts("?sort=price_asc");

            // assert
            List<ProductDto.ProductResponse> content = response.getBody().data().content();
            assertAll(
                () -> assertThat(content).hasSize(2),
                () -> assertThat(content.get(0).productId()).isEqualTo(notLiked.getId()),
                () -> assertThat(content.get(0).likeCount()).isEqualTo(0L),
                () -> assertThat(content.get(1).productId()).isEqualTo(liked.getId()),
                () -> assertThat(content.get(1).name()).isEqualTo("가방"),
                () -> assertThat(content.get(1).price()).isEqualTo(30_000L),
                () -> assertThat(content.get(1).brand().brandId()).isEqualTo(brand.getId()),
                () -> assertThat(content.get(1).brand().name()).isEqualTo("루퍼스"),
                () -> assertThat(content.get(1).likeCount()).isEqualTo(2L)
            );
        }

        @DisplayName("price_asc로 정렬하면 가격 오름차순이고, 가격이 같으면 이름 오름차순이다. (P-2)")
        @Test
        void returnsProductsByPriceAscThenName_whenSortIsPriceAsc() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Product expensive = saveProduct(brand, "가방", 30_000L);
            Product cheapB = saveProduct(brand, "나시", 10_000L);
            Product cheapA = saveProduct(brand, "가디건", 10_000L);

            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response = getProducts("?sort=price_asc");

            // assert
            assertThat(productIds(response)).containsExactly(cheapA.getId(), cheapB.getId(), expensive.getId());
        }

        @DisplayName("likes_desc로 정렬하면 좋아요 수 내림차순이고, 좋아요 수가 같으면 이름 오름차순이다. (P-2, 결정 2)")
        @Test
        void returnsProductsByLikesDescThenName_whenSortIsLikesDesc() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Product oneLikeB = saveProduct(brand, "나시", 10_000L);
            Product twoLikes = saveProduct(brand, "다운", 20_000L);
            Product oneLikeA = saveProduct(brand, "가방", 30_000L);
            Product noLike = saveProduct(brand, "라운드티", 5_000L);
            like(twoLikes, 2);
            like(oneLikeB, 1);
            like(oneLikeA, 1);

            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response = getProducts("?sort=likes_desc");

            // assert
            assertThat(productIds(response)).containsExactly(twoLikes.getId(), oneLikeA.getId(), oneLikeB.getId(), noLike.getId());
        }

        @DisplayName("likes_desc 정렬은 페이지를 자르기 전에 적용된다. (결정 2)")
        @Test
        void appliesLikesDescBeforePaging() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Product noLike = saveProduct(brand, "가방", 10_000L);
            Product liked = saveProduct(brand, "나시", 10_000L);
            like(liked, 1);

            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> firstPage = getProducts("?sort=likes_desc&page=0&size=1");
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> secondPage = getProducts("?sort=likes_desc&page=1&size=1");

            // assert
            assertAll(
                () -> assertThat(productIds(firstPage)).containsExactly(liked.getId()),
                () -> assertThat(productIds(secondPage)).containsExactly(noLike.getId()),
                () -> assertThat(firstPage.getBody().data().totalElements()).isEqualTo(2L),
                () -> assertThat(firstPage.getBody().data().totalPages()).isEqualTo(2)
            );
        }

        @DisplayName("brandId로 필터하면, 해당 브랜드의 상품만 반환한다.")
        @Test
        void returnsOnlyBrandProducts_whenBrandIdIsGiven() {
            // arrange
            Brand loopers = saveBrand("루퍼스");
            Brand other = saveBrand("다른브랜드");
            Product loopersProduct = saveProduct(loopers, "가방", 10_000L);
            saveProduct(other, "나시", 10_000L);

            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response = getProducts("?brandId=" + loopers.getId());

            // assert
            assertThat(productIds(response)).containsExactly(loopersProduct.getId());
        }

        @DisplayName("존재하지 않는 brandId로 필터하면, 404 NOT_FOUND 응답을 받는다. (P-10)")
        @Test
        void returnsNotFound_whenBrandIdDoesNotExist() {
            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response = getProducts("?brandId=999999");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("삭제된 brandId로 필터하면, 404 NOT_FOUND 응답을 받는다. (P-10)")
        @Test
        void returnsNotFound_whenBrandIsDeleted() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            brand.delete();
            brandJpaRepository.save(brand);

            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response = getProducts("?brandId=" + brand.getId());

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("지원하지 않는 sort 값을 주면, 400 BAD_REQUEST 응답을 받는다. (P-2)")
        @Test
        void returnsBadRequest_whenSortIsUnsupported() {
            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response = getProducts("?sort=name_desc");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("page가 음수이면, 400 BAD_REQUEST 응답을 받는다. (T-8)")
        @Test
        void returnsBadRequest_whenPageIsNegative() {
            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> response = getProducts("?page=-1");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("size가 1~100 범위를 벗어나면, 400 BAD_REQUEST 응답을 받는다. (T-8)")
        @Test
        void returnsBadRequest_whenSizeIsOutOfRange() {
            // act
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> zero = getProducts("?size=0");
            ResponseEntity<ApiResponse<PageResponse<ProductDto.ProductResponse>>> tooLarge = getProducts("?size=101");

            // assert
            assertAll(
                () -> assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(tooLarge.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
            );
        }
    }

    @DisplayName("GET /api/v1/products/{productId}")
    @Nested
    class GetProduct {

        @DisplayName("존재하는 상품 ID를 주면, 브랜드 정보와 좋아요 수를 포함한 상품을 반환한다.")
        @Test
        void returnsProductWithBrandAndLikeCount_whenProductExists() {
            // arrange
            Brand brand = saveBrand("루퍼스");
            Product product = saveProduct(brand, "가방", 30_000L);
            like(product, 3);

            // act
            ResponseEntity<ApiResponse<ProductDto.ProductResponse>> response = getProduct(String.valueOf(product.getId()));

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().productId()).isEqualTo(product.getId()),
                () -> assertThat(response.getBody().data().name()).isEqualTo("가방"),
                () -> assertThat(response.getBody().data().price()).isEqualTo(30_000L),
                () -> assertThat(response.getBody().data().brand().brandId()).isEqualTo(brand.getId()),
                () -> assertThat(response.getBody().data().brand().name()).isEqualTo("루퍼스"),
                () -> assertThat(response.getBody().data().likeCount()).isEqualTo(3L)
            );
        }

        @DisplayName("존재하지 않는 상품 ID를 주면, 404 NOT_FOUND 응답을 받는다. (QRY-001)")
        @Test
        void returnsNotFound_whenProductDoesNotExist() {
            // act
            ResponseEntity<ApiResponse<ProductDto.ProductResponse>> response = getProduct("999999");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("삭제된 상품 ID를 주면, 404 NOT_FOUND 응답을 받는다. (QRY-001)")
        @Test
        void returnsNotFound_whenProductIsDeleted() {
            // arrange
            Product product = saveProduct(saveBrand("루퍼스"), "가방", 30_000L);
            deleteProduct(product);

            // act
            ResponseEntity<ApiResponse<ProductDto.ProductResponse>> response = getProduct(String.valueOf(product.getId()));

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
