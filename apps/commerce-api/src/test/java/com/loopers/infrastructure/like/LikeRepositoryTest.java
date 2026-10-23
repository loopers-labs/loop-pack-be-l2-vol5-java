package com.loopers.infrastructure.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LikeRepositoryTest {

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private void saveLike(Long userId, Long productId) {
        likeRepository.save(Like.create(userId, productId));
    }

    @DisplayName("좋아요를 저장하고 확인할 때,")
    @Nested
    class SaveAndExists {
        @DisplayName("저장한 관계가 있다고 확인된다.")
        @Test
        void returnsTrue_whenLikeIsSaved() {
            // arrange
            saveLike(1L, 10L);

            // act & assert
            assertThat(likeRepository.exists(1L, 10L)).isTrue();
        }
    }

    @DisplayName("좋아요를 삭제할 때,")
    @Nested
    class Delete {
        @DisplayName("삭제한 행 수를 반환하고, 이후에는 관계가 없다. 이미 없으면 0을 반환한다.")
        @Test
        void returnsDeletedRowCount() {
            // arrange
            saveLike(1L, 10L);

            // act
            int first = likeRepository.delete(1L, 10L);
            int second = likeRepository.delete(1L, 10L);

            // assert
            assertThat(first).isEqualTo(1);
            assertThat(second).isZero();
            assertThat(likeRepository.exists(1L, 10L)).isFalse();
        }

        @DisplayName("다른 사용자나 다른 상품의 관계는 지우지 않는다.")
        @Test
        void keepsOtherLikes() {
            // arrange
            saveLike(1L, 10L);
            saveLike(2L, 10L);
            saveLike(1L, 11L);

            // act
            likeRepository.delete(1L, 10L);

            // assert
            assertThat(likeRepository.exists(2L, 10L)).isTrue();
            assertThat(likeRepository.exists(1L, 11L)).isTrue();
        }
    }

    @DisplayName("좋아요 테이블 스키마는,")
    @Nested
    class Schema {
        @DisplayName("기본키 컬럼 순서는 (product_id, user_id)이다.")
        @Test
        void hasPrimaryKeyOrderedByProductIdThenUserId() {
            // 상품별 좋아요 수 집계(product_id 조건)가 기본키의 왼쪽 접두로 인덱스를 타는 것이 이 순서에 달려 있다.
            // 그래서 product_id 보조 인덱스(idx_likes_product_id)는 두지 않는다. 이 단언이 그 인덱스가 하던 보장을 대신한다.
            // act & assert
            assertThat(indexColumns("PRIMARY")).containsExactly("product_id", "user_id");
        }

        @DisplayName("내 좋아요 목록을 위한 user_id 보조 인덱스를 가진다.")
        @Test
        void hasUserIdIndex() {
            // act & assert
            assertThat(indexColumns("idx_likes_user_id")).containsExactly("user_id");
        }

        // 인덱스의 컬럼을 인덱스 안의 순서대로 돌려준다.
        private List<String> indexColumns(String indexName) {
            return jdbcTemplate.queryForList(
                "select column_name from information_schema.statistics "
                    + "where table_schema = database() and table_name = 'likes' and index_name = ? "
                    + "order by seq_in_index",
                String.class,
                indexName
            );
        }
    }
}
