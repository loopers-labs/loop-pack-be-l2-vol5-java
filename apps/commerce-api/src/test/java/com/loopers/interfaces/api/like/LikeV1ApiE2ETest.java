package com.loopers.interfaces.api.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LikeV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new User());
        Brand brand = brandJpaRepository.save(new Brand("브랜드", null));
        product = productJpaRepository.save(new Product(brand, "상품", 1_000L));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static String likesOf(Long productId) {
        return "/api/v1/products/" + productId + "/likes";
    }

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
        return request.header(USER_ID_HEADER, String.valueOf(user.getId()));
    }

    private void deleteProduct() {
        product.delete();
        productJpaRepository.save(product);
    }

    @DisplayName("요청자를 확인할 때, ")
    @Nested
    class Identification {
        @DisplayName("헤더가 없으면, 401 과 UNAUTHENTICATED 를 돌려주고 관계가 만들어지지 않는다.")
        @Test
        void returnsUnauthenticated_whenHeaderIsMissing() throws Exception {
            mvc.perform(post(likesOf(product.getId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.meta.errorCode").value("UNAUTHENTICATED"));

            assertThat(likeJpaRepository.count()).isZero();
        }

        @DisplayName("숫자가 아니거나 없는 사용자면, 401 과 UNAUTHENTICATED 를 돌려준다.")
        @ParameterizedTest
        @ValueSource(strings = {"abc", "999999"})
        void returnsUnauthenticated_whenRequesterIsInvalid(String header) throws Exception {
            mvc.perform(post(likesOf(product.getId())).header(USER_ID_HEADER, header))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.meta.errorCode").value("UNAUTHENTICATED"));

            assertThat(likeJpaRepository.count()).isZero();
        }
    }

    @DisplayName("POST /api/v1/products/{productId}/likes")
    @Nested
    class Like_ {
        @DisplayName("등록하면 현재 좋아요 수를 돌려주고, 다시 등록해도 수가 늘지 않는다.")
        @Test
        void isIdempotent() throws Exception {
            mvc.perform(asUser(post(likesOf(product.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(product.getId()))
                .andExpect(jsonPath("$.data.likeCount").value(1));

            mvc.perform(asUser(post(likesOf(product.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(1));

            assertThat(likeJpaRepository.count()).isEqualTo(1);
        }

        @DisplayName("삭제된 상품이면, 404 와 PRODUCT_NOT_FOUND 를 돌려준다.")
        @Test
        void returnsNotFound_whenProductIsDeleted() throws Exception {
            deleteProduct();

            mvc.perform(asUser(post(likesOf(product.getId()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));

            assertThat(likeJpaRepository.count()).isZero();
        }
    }

    @DisplayName("DELETE /api/v1/products/{productId}/likes")
    @Nested
    class Unlike {
        @DisplayName("취소하면 현재 좋아요 수를 돌려주고, 다시 취소해도 성공한다.")
        @Test
        void isIdempotent() throws Exception {
            mvc.perform(asUser(post(likesOf(product.getId())))).andExpect(status().isOk());

            mvc.perform(asUser(delete(likesOf(product.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(0));
            mvc.perform(asUser(delete(likesOf(product.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(0));
        }

        @DisplayName("상품이 삭제되었어도, 남아 있는 자신의 관계를 취소할 수 있다.")
        @Test
        void cancelsRelation_evenWhenProductIsDeleted() throws Exception {
            mvc.perform(asUser(post(likesOf(product.getId())))).andExpect(status().isOk());
            deleteProduct();

            mvc.perform(asUser(delete(likesOf(product.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(0));

            assertThat(likeJpaRepository.count()).isZero();
        }

        @DisplayName("없는 상품이면, 요청한 상품 식별자와 좋아요 수 0 을 돌려준다.")
        @Test
        void succeeds_whenProductDoesNotExist() throws Exception {
            mvc.perform(asUser(delete(likesOf(999_999L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(999_999))
                .andExpect(jsonPath("$.data.likeCount").value(0));
        }
    }

    @DisplayName("GET /api/v1/users/{userId}/likes")
    @Nested
    class GetMyLikes {
        @DisplayName("내 좋아요 목록을 상품 요약 · 좋아요 시각과 함께 돌려주고, 삭제된 상품은 제외한다.")
        @Test
        void returnsMyLikes() throws Exception {
            mvc.perform(asUser(post(likesOf(product.getId())))).andExpect(status().isOk());
            Product deletedLater = productJpaRepository.save(new Product(brandJpaRepository.findAll().get(0), "삭제될 상품", 2_000L));
            mvc.perform(asUser(post(likesOf(deletedLater.getId())))).andExpect(status().isOk());
            deletedLater.delete();
            productJpaRepository.save(deletedLater);

            mvc.perform(asUser(get("/api/v1/users/" + user.getId() + "/likes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].productId").value(product.getId()))
                .andExpect(jsonPath("$.data.items[0].likeCount").value(1))
                .andExpect(jsonPath("$.data.items[0].soldOut").value(true))
                .andExpect(jsonPath("$.data.items[0].brand.name").value("브랜드"))
                .andExpect(jsonPath("$.data.items[0].likedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.totalCount").value(1));
        }

        @DisplayName("경로의 userId 가 요청자 본인이 아니면, 404 와 USER_NOT_FOUND 를 돌려준다.")
        @Test
        void returnsUserNotFound_whenUserIdIsNotRequester() throws Exception {
            User other = userJpaRepository.save(new User());

            mvc.perform(asUser(get("/api/v1/users/" + other.getId() + "/likes")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("USER_NOT_FOUND"));
        }

        @DisplayName("식별이 없으면, 401 과 UNAUTHENTICATED 를 돌려준다.")
        @Test
        void returnsUnauthenticated_whenHeaderIsMissing() throws Exception {
            mvc.perform(get("/api/v1/users/" + user.getId() + "/likes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.meta.errorCode").value("UNAUTHENTICATED"));
        }
    }
}
