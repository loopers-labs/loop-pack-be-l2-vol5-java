package com.loopers.interfaces.api.like;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LikeV1ApiE2ETest {

    private static final String USER_HEADER = "X-USER-ID";

    @Autowired
    private MockMvc mockMvc;

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

    private UserModel user;
    private BrandModel brand;

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new UserModel("고객"));
        brand = brandJpaRepository.save(new BrandModel("나이키", null));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ProductModel product(String name) {
        return productJpaRepository.save(new ProductModel(brand.getId(), name, 10_000, 5));
    }

    private ProductModel deletedProduct(String name) {
        ProductModel product = new ProductModel(brand.getId(), name, 10_000, 5);
        product.delete();
        return productJpaRepository.save(product);
    }

    private String likesPath(ProductModel product) {
        return "/api/v1/products/" + product.getId() + "/likes";
    }

    @DisplayName("POST /api/v1/products/{productId}/likes")
    @Nested
    class Like {

        @DisplayName("LIK-01 좋아요하면 관계가 저장되고, 상품의 좋아요 수에 반영된다.")
        @Test
        void likesProduct() throws Exception {
            // arrange
            ProductModel product = product("에어맥스");

            // act
            mockMvc.perform(post(likesPath(product)).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(product.getId()))
                .andExpect(jsonPath("$.data.liked").value(true));

            // assert
            assertThat(likeJpaRepository.count()).isEqualTo(1);
            mockMvc.perform(get("/api/v1/products/" + product.getId()))
                .andExpect(jsonPath("$.data.likeCount").value(1));
        }

        @DisplayName("LIK-02 이미 좋아요한 상품에 다시 요청해도 200이고, 관계는 하나다.")
        @Test
        void likingTwiceKeepsOneRelation() throws Exception {
            // arrange
            ProductModel product = product("에어맥스");

            // act
            mockMvc.perform(post(likesPath(product)).header(USER_HEADER, user.getId())).andExpect(status().isOk());
            mockMvc.perform(post(likesPath(product)).header(USER_HEADER, user.getId())).andExpect(status().isOk());

            // assert
            assertThat(likeJpaRepository.count()).isEqualTo(1);
        }

        @DisplayName("LIK-03 삭제된 상품에는 좋아요할 수 없어 404이고, 관계는 생기지 않는다.")
        @Test
        void rejectsDeletedProduct() throws Exception {
            // arrange
            ProductModel product = deletedProduct("단종");

            // act
            mockMvc.perform(post(likesPath(product)).header(USER_HEADER, user.getId()))
                .andExpect(status().isNotFound());

            // assert
            assertThat(likeJpaRepository.count()).isZero();
        }

        @DisplayName("USR-01 X-USER-ID가 없으면 401이고, 관계는 생기지 않는다.")
        @Test
        void rejectsUnidentifiedRequest() throws Exception {
            // arrange
            ProductModel product = product("에어맥스");

            // act
            mockMvc.perform(post(likesPath(product)))
                .andExpect(status().isUnauthorized());

            // assert
            assertThat(likeJpaRepository.count()).isZero();
        }
    }

    @DisplayName("DELETE /api/v1/products/{productId}/likes")
    @Nested
    class Unlike {

        @DisplayName("LIK-02 좋아요를 취소하면 관계가 지워진다.")
        @Test
        void unlikesProduct() throws Exception {
            // arrange
            ProductModel product = product("에어맥스");
            likeJpaRepository.save(new LikeModel(user.getId(), product.getId(), ZonedDateTime.now()));

            // act
            mockMvc.perform(delete(likesPath(product)).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false));

            // assert
            assertThat(likeJpaRepository.count()).isZero();
        }

        @DisplayName("LIK-02 좋아요하지 않은 상품을 취소해도 200이다.")
        @Test
        void unlikingWithoutRelationSucceeds() throws Exception {
            // arrange
            ProductModel product = product("에어맥스");

            // act & assert
            mockMvc.perform(delete(likesPath(product)).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk());
        }

        @DisplayName("LIK-03 삭제된 상품에 남은 내 좋아요도 취소할 수 있다.")
        @Test
        void unlikesDeletedProduct() throws Exception {
            // arrange
            ProductModel product = deletedProduct("단종");
            likeJpaRepository.save(new LikeModel(user.getId(), product.getId(), ZonedDateTime.now()));

            // act
            mockMvc.perform(delete(likesPath(product)).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk());

            // assert
            assertThat(likeJpaRepository.count()).isZero();
        }

        @DisplayName("다른 사용자의 좋아요는 지워지지 않는다.")
        @Test
        void doesNotRemoveOthersLike() throws Exception {
            // arrange
            ProductModel product = product("에어맥스");
            UserModel other = userJpaRepository.save(new UserModel("다른 고객"));
            likeJpaRepository.save(new LikeModel(other.getId(), product.getId(), ZonedDateTime.now()));

            // act
            mockMvc.perform(delete(likesPath(product)).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk());

            // assert
            assertThat(likeJpaRepository.findAll()).extracting(LikeModel::getUserId).containsExactly(other.getId());
        }
    }

    @DisplayName("GET /api/v1/users/{userId}/likes")
    @Nested
    class GetLikedProducts {

        @DisplayName("LIK-05 내 좋아요를 최근 순으로 돌려주고, 삭제된 상품과 다른 사람의 좋아요는 뺀다.")
        @Test
        void returnsOwnLikesExcludingDeleted() throws Exception {
            // arrange
            ProductModel older = product("먼저 좋아요");
            ProductModel deleted = deletedProduct("단종");
            ProductModel newer = product("나중 좋아요");
            UserModel other = userJpaRepository.save(new UserModel("다른 고객"));
            likeJpaRepository.save(new LikeModel(user.getId(), older.getId(), ZonedDateTime.now()));
            likeJpaRepository.save(new LikeModel(user.getId(), deleted.getId(), ZonedDateTime.now()));
            likeJpaRepository.save(new LikeModel(other.getId(), older.getId(), ZonedDateTime.now()));
            likeJpaRepository.save(new LikeModel(user.getId(), newer.getId(), ZonedDateTime.now()));

            // act & assert
            mockMvc.perform(get("/api/v1/users/" + user.getId() + "/likes").header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].productId").value(newer.getId()))
                .andExpect(jsonPath("$.data[1].productId").value(older.getId()))
                .andExpect(jsonPath("$.data[1].brand.name").value("나이키"))
                .andExpect(jsonPath("$.data[1].likedAt").exists());
        }

        @DisplayName("LIK-05 경로의 userId가 요청자와 다르면 404다.")
        @Test
        void rejectsOthersList() throws Exception {
            // arrange
            UserModel other = userJpaRepository.save(new UserModel("다른 고객"));

            // act & assert
            mockMvc.perform(get("/api/v1/users/" + other.getId() + "/likes").header(USER_HEADER, user.getId()))
                .andExpect(status().isNotFound());
        }
    }
}
