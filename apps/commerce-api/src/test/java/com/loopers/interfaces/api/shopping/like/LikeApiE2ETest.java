package com.loopers.interfaces.api.shopping.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.loopers.application.common.PageResult;
import com.loopers.application.mall.brand.BrandCommand;
import com.loopers.application.mall.brand.DeleteBrandUseCase;
import com.loopers.application.shopping.like.LikeItem;
import com.loopers.domain.mall.brand.Brand;
import com.loopers.domain.mall.brand.BrandRepository;
import com.loopers.domain.mall.product.Product;
import com.loopers.domain.mall.product.ProductRepository;
import com.loopers.domain.shopping.user.User;
import com.loopers.domain.shopping.user.UserRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeApiE2ETest {
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private DeleteBrandUseCase deleteBrandUseCase;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("좋아요 등록")
    @Nested
    class Register {
        @DisplayName("존재하는 사용자와 활성 상품이면 200을 반환하고 관계를 저장한다")
        @Test
        void registersLike() {
            User user = userRepository.save(User.create(1L));
            Product product = createProduct();

            ResponseEntity<ApiResponse<Object>> response = register(product.getId(), String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(countLikes(user.getId(), product.getId())).isEqualTo(1L)
            );
        }

        @DisplayName("이미 등록된 관계는 다시 등록해도 200이며 관계는 하나로 유지된다")
        @Test
        void ignoresDuplicateRegistration() {
            User user = userRepository.save(User.create(1L));
            Product product = createProduct();
            register(product.getId(), String.valueOf(user.getId()));

            ResponseEntity<ApiResponse<Object>> response = register(product.getId(), String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(countLikes(user.getId(), product.getId())).isEqualTo(1L)
            );
        }

        @DisplayName("X-USER-ID 헤더가 없으면 400을 반환한다")
        @Test
        void returnsBadRequest_whenHeaderIsMissing() {
            Product product = createProduct();

            ResponseEntity<ApiResponse<Object>> response = register(product.getId(), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("X-USER-ID 헤더가 양의 정수가 아니면 400을 반환한다")
        @Test
        void returnsBadRequest_whenHeaderIsInvalid() {
            Product product = createProduct();

            ResponseEntity<ApiResponse<Object>> response = register(product.getId(), "abc");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("존재하지 않는 사용자면 404를 반환한다")
        @Test
        void returnsNotFound_whenUserDoesNotExist() {
            Product product = createProduct();

            ResponseEntity<ApiResponse<Object>> response = register(product.getId(), "999");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("삭제된 상품이면 404를 반환하고 관계를 만들지 않는다")
        @Test
        void returnsNotFound_whenProductIsDeleted() {
            User user = userRepository.save(User.create(1L));
            Product product = createProduct();
            product.delete();
            productRepository.save(product);

            ResponseEntity<ApiResponse<Object>> response = register(product.getId(), String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(countLikes(user.getId(), product.getId())).isZero()
            );
        }
    }

    @DisplayName("좋아요 취소")
    @Nested
    class Cancel {
        @DisplayName("존재하는 관계를 취소하면 200을 반환하고 관계를 제거한다")
        @Test
        void cancelsLike() {
            User user = userRepository.save(User.create(1L));
            Product product = createProduct();
            register(product.getId(), String.valueOf(user.getId()));

            ResponseEntity<ApiResponse<Object>> response = cancel(product.getId(), String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(countLikes(user.getId(), product.getId())).isZero()
            );
        }

        @DisplayName("관계가 없어도 200을 그대로 반환한다")
        @Test
        void ignoresCancelOfMissingRelation() {
            User user = userRepository.save(User.create(1L));
            Product product = createProduct();

            ResponseEntity<ApiResponse<Object>> response = cancel(product.getId(), String.valueOf(user.getId()));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @DisplayName("브랜드 일괄 삭제로 상품이 삭제되어도 기존 좋아요 취소는 그대로 동작한다")
        @Test
        void cancelsLike_whenProductWasDeletedViaBrandBulkDelete() {
            User user = userRepository.save(User.create(1L));
            Brand brand = brandRepository.save(Brand.create("브랜드", null));
            Product product = productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, 5));
            register(product.getId(), String.valueOf(user.getId()));

            deleteBrandUseCase.execute(new BrandCommand.Delete(brand.getId()));
            ResponseEntity<ApiResponse<Object>> response = cancel(product.getId(), String.valueOf(user.getId()));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(countLikes(user.getId(), product.getId())).isZero()
            );
        }
    }

    @DisplayName("내 좋아요 목록")
    @Nested
    class FindMyLikes {
        @DisplayName("사용자의 좋아요 목록을 반환한다")
        @Test
        void returnsLikedProducts() {
            User user = userRepository.save(User.create(1L));
            Product product = createProduct();
            register(product.getId(), String.valueOf(user.getId()));

            ResponseEntity<ApiResponse<PageResult<LikeItem>>> response = restTemplate.exchange(
                "/api/v1/users/" + user.getId() + "/likes",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {}
            );

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().totalElements()).isEqualTo(1L),
                () -> assertThat(response.getBody().data().items().get(0).productId()).isEqualTo(product.getId())
            );
        }

        @DisplayName("존재하지 않는 사용자면 404를 반환한다")
        @Test
        void returnsNotFound_whenUserDoesNotExist() {
            ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                "/api/v1/users/999/likes",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private Product createProduct() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        return productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, 5));
    }

    private ResponseEntity<ApiResponse<Object>> register(long productId, String userIdHeader) {
        return restTemplate.exchange(
            "/api/v1/products/" + productId + "/likes",
            HttpMethod.POST,
            new HttpEntity<>(null, headers(userIdHeader)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<Object>> cancel(long productId, String userIdHeader) {
        return restTemplate.exchange(
            "/api/v1/products/" + productId + "/likes",
            HttpMethod.DELETE,
            new HttpEntity<>(null, headers(userIdHeader)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private HttpHeaders headers(String userIdHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (userIdHeader != null) {
            headers.add("X-USER-ID", userIdHeader);
        }
        return headers;
    }

    private long countLikes(long userId, long productId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM product_likes WHERE user_id = :userId AND product_id = :productId")
            .param("userId", userId)
            .param("productId", productId)
            .query(Long.class)
            .single();
    }
}
