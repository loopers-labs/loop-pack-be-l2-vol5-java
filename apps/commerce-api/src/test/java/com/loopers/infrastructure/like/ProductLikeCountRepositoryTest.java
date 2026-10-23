package com.loopers.infrastructure.like;

import com.loopers.domain.like.ProductLikeCountRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductLikeCountRepositoryTest {

    @Autowired
    private ProductLikeCountRepository productLikeCountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private void increase(Long productId, int times) {
        for (int i = 0; i < times; i++) {
            productLikeCountRepository.increase(productId);
        }
    }

    private long rows(Long productId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from product_like_counts where product_id = ?", Long.class, productId);
    }

    @DisplayName("행을 만들고 지울 때,")
    @Nested
    class CreateAndDelete {
        @DisplayName("새 행은 좋아요 0개로 만들어진다.")
        @Test
        void createsRowWithZero() {
            // act
            productLikeCountRepository.create(10L);

            // assert
            assertThat(rows(10L)).isEqualTo(1);
            assertThat(productLikeCountRepository.findCount(10L)).isZero();
        }

        @DisplayName("요청한 상품의 행만 지운다. 빈 목록이면 아무것도 지우지 않는다.")
        @Test
        void deletesOnlyRequestedRows() {
            // arrange
            productLikeCountRepository.create(10L);
            productLikeCountRepository.create(11L);
            productLikeCountRepository.create(12L);

            // act
            productLikeCountRepository.deleteAll(List.of());
            productLikeCountRepository.deleteAll(List.of(10L, 11L));

            // assert
            assertThat(rows(10L)).isZero();
            assertThat(rows(11L)).isZero();
            assertThat(rows(12L)).isEqualTo(1);
        }
    }

    @DisplayName("좋아요 수를 올리고 내릴 때,")
    @Nested
    class IncreaseAndDecrease {
        @DisplayName("1씩 올리고 내리며, 바뀐 행 수 1을 반환한다.")
        @Test
        void changesByOne() {
            // arrange
            productLikeCountRepository.create(10L);

            // act & assert
            assertThat(productLikeCountRepository.increase(10L)).isEqualTo(1);
            assertThat(productLikeCountRepository.increase(10L)).isEqualTo(1);
            assertThat(productLikeCountRepository.decrease(10L)).isEqualTo(1);
            assertThat(productLikeCountRepository.findCount(10L)).isEqualTo(1);
        }

        @DisplayName("0 아래로는 내리지 않고, 바뀐 행 수 0을 반환한다.")
        @Test
        void doesNotGoBelowZero() {
            // arrange
            productLikeCountRepository.create(10L);

            // act & assert
            assertThat(productLikeCountRepository.decrease(10L)).isZero();
            assertThat(productLikeCountRepository.findCount(10L)).isZero();
        }

        @DisplayName("행이 없는 상품(삭제된 상품)은 행을 새로 만들지 않고 바뀐 행 수 0을 반환한다.")
        @Test
        void doesNotCreateRow_whenRowIsMissing() {
            // act & assert
            assertThat(productLikeCountRepository.increase(10L)).isZero();
            assertThat(productLikeCountRepository.decrease(10L)).isZero();
            assertThat(rows(10L)).isZero();
        }
    }

    @DisplayName("상품의 좋아요 수를 읽을 때,")
    @Nested
    class FindCount {
        @DisplayName("상품의 집계 값을 돌려준다. 다른 상품의 값은 섞이지 않는다.")
        @Test
        void returnsAggregatedCountOfProduct() {
            // arrange
            productLikeCountRepository.create(10L);
            productLikeCountRepository.create(11L);
            increase(10L, 2);
            increase(11L, 1);

            // act & assert
            assertThat(productLikeCountRepository.findCount(10L)).isEqualTo(2);
        }
    }

    @DisplayName("여러 상품의 좋아요 수를 한 번에 읽을 때,")
    @Nested
    class FindCounts {
        @DisplayName("행이 있으면 0도 담고, 행이 없는 상품은 결과에 없다.")
        @Test
        void returnsCountsOfExistingRows_includingZero() {
            // arrange: 12L 은 행이 있고 0이며, 13L 은 행이 없다.
            productLikeCountRepository.create(10L);
            productLikeCountRepository.create(11L);
            productLikeCountRepository.create(12L);
            increase(10L, 2);
            increase(11L, 1);

            // act
            Map<Long, Long> counts = productLikeCountRepository.findCounts(List.of(10L, 11L, 12L, 13L));

            // assert
            assertThat(counts).containsOnly(Map.entry(10L, 2L), Map.entry(11L, 1L), Map.entry(12L, 0L));
        }
    }

    @DisplayName("좋아요순으로 페이지의 상품 id 를 읽을 때,")
    @Nested
    class FindRankedProductIds {
        @DisplayName("좋아요 수 내림차순이고 같으면 상품 id 내림차순이며, 페이지 크기만큼 읽는다.")
        @Test
        void returnsIdsOrderedByCountThenIdDesc_perPage() {
            // arrange: 10L·12L 은 2개로 같고, 11L 은 3개, 13L 은 0개다.
            for (long id = 10; id <= 13; id++) {
                productLikeCountRepository.create(id);
            }
            increase(10L, 2);
            increase(11L, 3);
            increase(12L, 2);

            // act
            List<Long> first = productLikeCountRepository.findRankedProductIds(PageRequest.of(0, 3));
            List<Long> second = productLikeCountRepository.findRankedProductIds(PageRequest.of(1, 3));

            // assert
            assertThat(first).containsExactly(11L, 12L, 10L);
            assertThat(second).containsExactly(13L);
        }
    }

    @DisplayName("좋아요 수 집계 테이블 스키마는,")
    @Nested
    class Schema {
        @DisplayName("(like_count DESC, product_id DESC) 순위 인덱스를 가진다.")
        @Test
        void hasLikeCountRankingIndex() {
            // 브랜드 없는 좋아요순 목록이 이 인덱스 순서로 페이지의 상품 id 만 읽는다. 정렬 방향이 어긋나면 filesort 가 생긴다.
            List<String> columns = jdbcTemplate.queryForList(
                "select concat(column_name, ':', collation) from information_schema.statistics "
                    + "where table_schema = database() and table_name = 'product_like_counts' "
                    + "and index_name = 'idx_product_like_counts_ranking' order by seq_in_index",
                String.class
            );

            assertThat(columns).containsExactly("like_count:D", "product_id:D");
        }

        @DisplayName("기본키는 product_id 이다.")
        @Test
        void hasProductIdPrimaryKey() {
            List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.statistics "
                    + "where table_schema = database() and table_name = 'product_like_counts' "
                    + "and index_name = 'PRIMARY' order by seq_in_index",
                String.class
            );

            assertThat(columns).containsExactly("product_id");
        }
    }
}
