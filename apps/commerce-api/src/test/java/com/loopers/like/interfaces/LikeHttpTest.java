package com.loopers.like.interfaces;

import com.loopers.brand.domain.Brand;
import com.loopers.product.domain.Product;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static com.loopers.support.http.ApiHttp.body;
import static com.loopers.support.http.ApiHttp.content;
import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.data;
import static com.loopers.support.http.ApiHttp.failure;
import static com.loopers.support.http.ApiHttp.findById;
import static com.loopers.support.http.ApiHttp.ids;
import static com.loopers.support.http.ApiHttp.isNullOrAbsent;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class LikeHttpTest {

    private static final String PRODUCT_LIKES = "/api/v1/products/{productId}/likes";
    private static final String USER_LIKES = "/api/v1/users/{userId}/likes";
    private static final String CUSTOMER_PRODUCT = "/api/v1/products/{productId}";
    private static final String CUSTOMER_PRODUCTS = "/api/v1/products";
    private static final long MISSING_ID = 999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        fixture.truncateRemainingTables();
    }

    @DisplayName("[R-LIKE-01] 고객은 삭제되지 않은 상품에 좋아요를 등록하고 자신의 좋아요를 취소할 수 있다.")
    @Nested
    class RegisterAndCancel {

        @DisplayName("[상태 전이] 좋아요를 등록하면 201이고 data가 없으며, 내 좋아요 목록에 그 상품이 담긴다.")
        @Test
        void registersLike() throws Exception {
            // arrange
            User customer = fixture.user();
            Product product = product("Air");

            // act
            MvcResult result = mockMvc.perform(register(customer, product))
                .andExpect(success(HttpStatus.CREATED))
                .andReturn();

            // assert
            assertThat(isNullOrAbsent(body(result), "data")).isTrue();
            assertThat(myLikeIds(customer)).containsExactly(product.getId());
        }

        @DisplayName("[상태 전이] 자신의 좋아요를 취소하면 200이고 data가 없으며, 내 좋아요 목록에서 그 상품이 빠진다.")
        @Test
        void cancelsOwnLike() throws Exception {
            // arrange
            User customer = fixture.user();
            Product product = product("Air");
            fixture.like(customer, product);

            // act
            MvcResult result = mockMvc.perform(cancel(customer, product))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertThat(isNullOrAbsent(body(result), "data")).isTrue();
            assertThat(myLikeIds(customer)).isEmpty();
        }
    }

    @DisplayName("[R-LIKE-04] 고객은 자신의 좋아요 관계만 조회하고 취소할 수 있다.")
    @Nested
    class OnlyOwnLikes {

        @DisplayName("[동등 클래스 분할] 내 좋아요 목록에는 내가 좋아요한 상품만 있고 다른 고객이 좋아요한 상품은 없다.")
        @Test
        void listsOnlyOwnLikes() throws Exception {
            // arrange
            User me = fixture.user();
            User other = fixture.user();
            Product mine = product("Mine");
            Product others = product("Others");
            fixture.like(me, mine);
            fixture.like(other, others);

            // act & assert
            assertThat(myLikeIds(me)).containsExactly(mine.getId());
        }

        @DisplayName("[오류 추측] 다른 고객만 좋아요한 상품에 취소를 요청해도 다른 고객의 좋아요는 남고 좋아요 수는 1 그대로다.")
        @Test
        void keepsOthersLikeOnCancel() throws Exception {
            // arrange
            User me = fixture.user();
            User other = fixture.user();
            Product product = product("Air");
            fixture.like(other, product);

            // act
            mockMvc.perform(cancel(me, product))
                .andExpect(success(HttpStatus.OK));

            // assert
            assertAll(
                () -> assertThat(myLikeIds(other)).containsExactly(product.getId()),
                () -> assertThat(likeCount(me, product)).isEqualTo(1L)
            );
        }
    }

    @DisplayName("[R-LIKE-06] 좋아요 등록과 취소 결과는 이후 조회하는 상품의 좋아요 수에 반영된다.")
    @Nested
    class ReflectLikeCount {

        @DisplayName("[상태 전이] 두 고객이 등록하고 한 고객이 취소하면 상품 상세의 likeCount가 0, 1, 2, 1로 바뀌고 목록도 1이다.")
        @Test
        void reflectsRegistrationsAndCancellation() throws Exception {
            // arrange
            User first = fixture.user();
            User second = fixture.user();
            Product product = product("Air");
            long initial = likeCount(first, product);

            // act
            mockMvc.perform(register(first, product)).andExpect(success(HttpStatus.CREATED));
            long afterFirst = likeCount(first, product);
            mockMvc.perform(register(second, product)).andExpect(success(HttpStatus.CREATED));
            long afterSecond = likeCount(first, product);
            mockMvc.perform(cancel(first, product)).andExpect(success(HttpStatus.OK));
            long afterCancel = likeCount(first, product);

            // assert
            MvcResult list = mockMvc.perform(get(CUSTOMER_PRODUCTS).with(customer(first)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            assertAll(
                () -> assertThat(List.of(initial, afterFirst, afterSecond, afterCancel)).containsExactly(0L, 1L, 2L, 1L),
                () -> assertThat(findById(data(list).path("content"), product.getId()).path("likeCount").asLong(-1))
                    .isEqualTo(1L)
            );
        }
    }

    @DisplayName("[P-ACCESS-02] 고객이 다른 고객의 좋아요 목록을 요청하면 없는 대상으로 알린다.")
    @Nested
    class OthersLikesAsNotFound {

        @DisplayName("[동등 클래스 분할] 다른 고객의 좋아요 목록은 404 USER_NOT_FOUND이고, 존재하지 않는 사용자의 목록을 요청한 응답과 같다.")
        @Test
        void hidesOthersLikes() throws Exception {
            // arrange
            User me = fixture.user();
            User other = fixture.user();
            fixture.like(other, product("Air"));

            // act
            MvcResult others = mockMvc.perform(get(USER_LIKES, other.getId()).with(customer(me)))
                .andExpect(failure(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"))
                .andReturn();
            MvcResult missing = mockMvc.perform(get(USER_LIKES, MISSING_ID).with(customer(me)))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(missing.getResponse().getStatus()).isEqualTo(others.getResponse().getStatus()),
                () -> assertThat(content(missing)).isEqualTo(content(others))
            );
        }
    }

    @DisplayName("[P-LIKE-01] 이미 좋아요한 상품을 다시 좋아요하거나 좋아요하지 않은 상품을 취소해도 성공하고, 좋아요 수는 달라지지 않는다.")
    @Nested
    class IdempotentLikeRequests {

        @DisplayName("[상태 전이] 같은 상품에 두 번 등록하면 201, 200이고 likeCount는 1이다.")
        @Test
        void repeatsRegistration() throws Exception {
            // arrange
            User customer = fixture.user();
            Product product = product("Air");

            // act
            mockMvc.perform(register(customer, product)).andExpect(success(HttpStatus.CREATED));
            mockMvc.perform(register(customer, product)).andExpect(success(HttpStatus.OK));

            // assert
            assertThat(likeCount(customer, product)).isEqualTo(1L);
        }

        @DisplayName("[상태 전이] 좋아요를 두 번 취소하면 둘 다 200이고 likeCount는 0이다.")
        @Test
        void repeatsCancellation() throws Exception {
            // arrange
            User customer = fixture.user();
            Product product = product("Air");
            fixture.like(customer, product);

            // act
            mockMvc.perform(cancel(customer, product)).andExpect(success(HttpStatus.OK));
            mockMvc.perform(cancel(customer, product)).andExpect(success(HttpStatus.OK));

            // assert
            assertThat(likeCount(customer, product)).isZero();
        }

        @DisplayName("[상태 전이] 좋아요한 적 없는 상품을 취소해도 200이고 likeCount는 0이다.")
        @Test
        void cancelsWithoutLike() throws Exception {
            // arrange
            User customer = fixture.user();
            Product product = product("Air");

            // act
            mockMvc.perform(cancel(customer, product)).andExpect(success(HttpStatus.OK));

            // assert
            assertThat(likeCount(customer, product)).isZero();
        }
    }

    @DisplayName("[P-LIKE-02] 삭제된 상품에 좋아요를 등록하려 하면 없는 대상으로 알린다.")
    @Nested
    class DeletedProductAsNotFound {

        @DisplayName("[동등 클래스 분할] 삭제된 상품에 등록하면 404 PRODUCT_NOT_FOUND이고, 존재하지 않는 상품에 등록한 응답과 같다.")
        @Test
        void rejectsDeletedProduct() throws Exception {
            // arrange
            User customer = fixture.user();
            Product deleted = fixture.deletedProduct(fixture.brand("Nike"), "Old", 1_000L);

            // act
            MvcResult deletedResult = mockMvc.perform(register(customer, deleted))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"))
                .andReturn();
            MvcResult missingResult = mockMvc.perform(post(PRODUCT_LIKES, MISSING_ID)
                    .with(customer(customer)).with(csrf()))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(missingResult.getResponse().getStatus())
                    .isEqualTo(deletedResult.getResponse().getStatus()),
                () -> assertThat(content(missingResult)).isEqualTo(content(deletedResult))
            );
        }
    }

    private Product product(String name) {
        Brand brand = fixture.brand("Brand " + name);
        return fixture.product(brand, name, 1_000L, 5);
    }

    private MockHttpServletRequestBuilder register(User customer, Product product) {
        return post(PRODUCT_LIKES, product.getId()).with(customer(customer)).with(csrf());
    }

    private MockHttpServletRequestBuilder cancel(User customer, Product product) {
        return delete(PRODUCT_LIKES, product.getId()).with(customer(customer)).with(csrf());
    }

    private List<Long> myLikeIds(User customer) throws Exception {
        MvcResult result = mockMvc.perform(get(USER_LIKES, customer.getId()).with(customer(customer)))
            .andExpect(success(HttpStatus.OK))
            .andReturn();
        return ids(data(result).path("content"));
    }

    private long likeCount(User requester, Product product) throws Exception {
        MvcResult result = mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId()).with(customer(requester)))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.likeCount").exists())
            .andReturn();
        return data(result).path("likeCount").asLong();
    }
}
