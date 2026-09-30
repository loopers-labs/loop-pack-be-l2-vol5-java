package com.loopers.infrastructure.persistence.shopping.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class LikeStorageIntegrationTest {
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("좋아요 관계 저장")
    @Nested
    class SaveLike {
        @DisplayName("같은 사용자와 상품 관계는 하나만 저장한다")
        @Test
        void enforcesUniqueUserProductRelation() {
            insertLike(1L, 10L);

            assertThatThrownBy(() -> insertLike(1L, 10L)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM product_likes WHERE user_id = :userId AND product_id = :productId")
                .param("userId", 1L)
                .param("productId", 10L)
                .query(Long.class)
                .single()).isEqualTo(1L);
        }

        private void insertLike(long userId, long productId) {
            jdbcClient.sql(
                    "INSERT INTO product_likes (user_id, product_id, created_at) VALUES (:userId, :productId, CURRENT_TIMESTAMP)")
                .param("userId", userId)
                .param("productId", productId)
                .update();
        }
    }

    @DisplayName("상품별 집계 저장")
    @Nested
    class SaveCount {
        @DisplayName("상품 ID를 유일 키로 좋아요 수를 저장한다")
        @Test
        void storesCountByProductId() {
            jdbcClient.sql("INSERT INTO product_like_counts (product_id, like_count) VALUES (:productId, :likeCount)")
                .param("productId", 10L)
                .param("likeCount", 3L)
                .update();

            assertThatThrownBy(() -> jdbcClient.sql(
                    "INSERT INTO product_like_counts (product_id, like_count) VALUES (:productId, :likeCount)")
                .param("productId", 10L)
                .param("likeCount", 5L)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbcClient.sql("SELECT like_count FROM product_like_counts WHERE product_id = :productId")
                .param("productId", 10L)
                .query(Long.class)
                .single()).isEqualTo(3L);
        }
    }
}
