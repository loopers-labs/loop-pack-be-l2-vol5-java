package com.loopers.domain.like;

import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LikeServiceTest {

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private LikeService likeService;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long saveProduct() {
        return exposedProducts.save(Product.create(1L, "상품", new Money(1_000), new Stock(1))).getId();
    }

    @DisplayName("좋아요를 등록할 때,")
    @Nested
    class Register {
        @DisplayName("관계를 저장한다.")
        @Test
        void savesLike() {
            // arrange
            Long product = saveProduct();

            // act
            likeService.like(1L, product);

            // assert
            assertThat(likeRepository.exists(1L, product)).isTrue();
        }

        @DisplayName("같은 관계를 다시 등록해도 성공하고, 관계는 하나만 유지된다.")
        @Test
        void keepsSingleLike_whenLikedTwice() {
            // arrange
            Long product = saveProduct();

            // act
            likeService.like(1L, product);
            likeService.like(1L, product);

            // assert
            assertThat(likeService.count(product)).isEqualTo(1);
        }

        @DisplayName("다른 사용자의 좋아요는 각각 센다.")
        @Test
        void countsEachUser() {
            // arrange
            Long product = saveProduct();

            // act
            likeService.like(1L, product);
            likeService.like(2L, product);

            // assert
            assertThat(likeService.count(product)).isEqualTo(2);
        }
    }

    @DisplayName("좋아요를 취소할 때,")
    @Nested
    class Unlike {
        @DisplayName("관계를 지운다.")
        @Test
        void removesLike() {
            // arrange
            Long product = saveProduct();
            likeService.like(1L, product);

            // act
            likeService.unlike(1L, product);

            // assert
            assertThat(likeRepository.exists(1L, product)).isFalse();
            assertThat(likeService.count(product)).isZero();
        }

        @DisplayName("관계가 없어도 성공하고, 다른 관계는 그대로다.")
        @Test
        void succeeds_whenLikeDoesNotExist() {
            // arrange
            Long product = saveProduct();
            likeService.like(2L, product);

            // act
            likeService.unlike(1L, product);

            // assert
            assertThat(likeService.count(product)).isEqualTo(1);
        }

        @DisplayName("등록과 취소를 거친 뒤 좋아요 수는 좋아요 관계의 개수와 같다.")
        @Test
        void countMatchesNumberOfLikeRows() {
            // arrange
            Long product = saveProduct();
            likeService.like(1L, product);
            likeService.like(2L, product);
            likeService.like(3L, product);

            // act
            likeService.unlike(2L, product);

            // assert
            Long rows = jdbcTemplate.queryForObject("select count(*) from likes where product_id = ?", Long.class, product);
            assertThat(likeService.count(product)).isEqualTo(2).isEqualTo(rows);
        }
    }

    @DisplayName("상품의 좋아요 수를 셀 때,")
    @Nested
    class Count {
        @DisplayName("관계가 없으면 0이다.")
        @Test
        void returnsZero_whenNoLikes() {
            assertThat(likeService.count(10L)).isZero();
        }
    }

    @DisplayName("여러 상품의 좋아요 수를 한 번에 셀 때,")
    @Nested
    class CountByProductIds {
        @DisplayName("요청한 모든 상품의 수를 반환하고, 좋아요가 없는 상품은 0으로 채운다.")
        @Test
        void fillsZero_forProductsWithoutLikes() {
            // arrange
            Long liked = saveProduct();
            Long noLikes = saveProduct();
            likeService.like(1L, liked);
            likeService.like(2L, liked);

            // act
            Map<Long, Long> counts = likeService.countByProductIds(List.of(liked, noLikes));

            // assert
            assertThat(counts).containsOnly(Map.entry(liked, 2L), Map.entry(noLikes, 0L));
        }

        @DisplayName("빈 목록이면 빈 맵을 반환한다.")
        @Test
        void returnsEmpty_whenIdsAreEmpty() {
            assertThat(likeService.countByProductIds(List.of())).isEmpty();
        }
    }
}
