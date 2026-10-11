package com.loopers.interfaces.api.productlike;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.ApiTestClient;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** 좋아요 EP-04~06 의 HTTP 계약 (설계 4-3, 4-4). 테스트 이름에 ER-ID. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductLikeV1ApiE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private ApiTestClient api;
    private UserModel user;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(restTemplate, objectMapper);
        user = fixtures.user();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("EP-04/05/06 좋아요")
    class Likes {
        @DisplayName("EP-04 등록·EP-05 취소는 200 + data null. EP-06 목록에 반영된다.")
        @Test
        void likeUnlikeList() {
            BrandModel brand = fixtures.brand("브랜드");
            ProductModel product = fixtures.product(brand.getId(), "상품", 1000L, 1);
            String likesPath = "/api/v1/products/" + product.getId() + "/likes";
            String myLikesPath = "/api/v1/users/" + user.getId() + "/likes";

            var liked = api.post(likesPath, user.getId(), null).assertSuccess(HttpStatus.OK);
            assertThat(liked.data().isNull() || liked.data().isMissingNode()).isTrue();

            var list = api.get(myLikesPath, user.getId()).assertSuccess(HttpStatus.OK);
            assertThat(list.data().path("totalCount").asLong()).isEqualTo(1);
            var item = list.data().path("items").get(0);
            assertThat(item.path("likedAt").asText()).isNotBlank();
            assertThat(item.path("product").path("id").asLong()).isEqualTo(product.getId());
            assertThat(item.path("product").path("brand").path("id").asLong()).isEqualTo(brand.getId());

            api.delete(likesPath, user.getId()).assertSuccess(HttpStatus.OK);
            assertThat(api.get(myLikesPath, user.getId()).data().path("totalCount").asLong()).isZero();
        }

        @DisplayName("[ER-04 PRODUCT_NOT_FOUND] 없는 상품에 등록·취소는 404.")
        @Test
        void productNotFound() {
            api.post("/api/v1/products/999999/likes", user.getId(), null).assertError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
            api.delete("/api/v1/products/999999/likes", user.getId()).assertError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        }

        @DisplayName("[ER-06 NOT_OWNER] 다른 사용자의 좋아요 목록은 403.")
        @Test
        void notOwner() {
            UserModel other = fixtures.user();

            api.get("/api/v1/users/" + other.getId() + "/likes", user.getId()).assertError(HttpStatus.FORBIDDEN, "NOT_OWNER");
        }

        @DisplayName("[ER-08 INVALID_PAGE] 내 좋아요 목록 페이지 오류는 400.")
        @Test
        void invalidPage() {
            api.get("/api/v1/users/" + user.getId() + "/likes?size=101", user.getId()).assertError(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
    }
}
