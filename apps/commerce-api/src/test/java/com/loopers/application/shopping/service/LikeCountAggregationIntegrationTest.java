package com.loopers.application.shopping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.loopers.application.shopping.usecase.LikeCountAggregationUseCase;
import com.loopers.infrastructure.dao.shopping.JdbcLikeCountAggregationDao;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class LikeCountAggregationIntegrationTest {
    @Autowired
    private LikeCountAggregationUseCase aggregationUseCase;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private JdbcLikeCountAggregationDao aggregationDao;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("전체 관계 COUNT를 저장하고 관계가 사라진 기존 집계는 0으로 갱신한다")
    @Test
    void aggregatesAllCounts_andResetsStaleCount() {
        insertLike(1L, 10L);
        insertLike(2L, 10L);
        jdbcClient.sql("INSERT INTO product_like_counts (product_id, like_count) VALUES (99, 4)").update();

        aggregationUseCase.execute();

        assertThat(findCount(10L)).isEqualTo(2L);
        assertThat(findCount(99L)).isZero();
    }

    @DisplayName("집계 저장 중 실패하면 앞선 0 초기화도 함께 롤백한다")
    @Test
    void rollsBackAllCounts_whenAggregationFails() {
        insertLike(1L, 10L);
        jdbcClient.sql("INSERT INTO product_like_counts (product_id, like_count) VALUES (10, 7)").update();
        doThrow(new IllegalStateException("forced aggregation failure"))
            .when(aggregationDao).aggregateAllCounts();

        try {
            assertThatThrownBy(aggregationUseCase::execute).isInstanceOf(RuntimeException.class);

            assertThat(findCount(10L)).isEqualTo(7L);
        } finally {
            reset(aggregationDao);
        }
    }

    @DisplayName("증감분 반영은 집계 행이 없으면 생성한다")
    @Test
    void addDeltas_createsMissingRow() {
        aggregationDao.addDeltas(Map.of(10L, 3L));

        assertThat(findCount(10L)).isEqualTo(3L);
    }

    @DisplayName("증감분 반영은 기존 집계 값에 더한다")
    @Test
    void addDeltas_addsToExistingCount() {
        jdbcClient.sql("INSERT INTO product_like_counts (product_id, like_count) VALUES (10, 5)").update();
        jdbcClient.sql("INSERT INTO product_like_counts (product_id, like_count) VALUES (20, 5)").update();

        aggregationDao.addDeltas(Map.of(10L, 2L, 20L, -3L));

        assertThat(findCount(10L)).isEqualTo(7L);
        assertThat(findCount(20L)).isEqualTo(2L);
    }

    @DisplayName("증감분 반영은 결과가 음수가 되면 0으로 맞춘다")
    @Test
    void addDeltas_clampsAtZero() {
        jdbcClient.sql("INSERT INTO product_like_counts (product_id, like_count) VALUES (10, 1)").update();

        aggregationDao.addDeltas(Map.of(10L, -5L, 30L, -2L));

        assertThat(findCount(10L)).isZero();
        assertThat(findCount(30L)).isZero();
    }

    private void insertLike(long userId, long productId) {
        jdbcClient.sql("""
                INSERT INTO product_likes (user_id, product_id, created_at)
                VALUES (:userId, :productId, CURRENT_TIMESTAMP)
                """)
            .param("userId", userId)
            .param("productId", productId)
            .update();
    }

    private long findCount(long productId) {
        return jdbcClient.sql("SELECT like_count FROM product_like_counts WHERE product_id = :productId")
            .param("productId", productId)
            .query(Long.class)
            .single();
    }

}
