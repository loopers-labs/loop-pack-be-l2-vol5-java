package com.loopers.infrastructure.like;

import com.loopers.application.like.port.LikeRepository;
import com.loopers.domain.like.Like;
import com.loopers.domain.product.ProductId;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// like_count ±1 여부를 관계 저장·삭제의 영향 행 수로 판단하므로, 실제 MySQL 드라이버가 돌려주는 값을 확인한다.
@SpringBootTest
class LikePersistenceIntegrationTest {
    @Autowired private LikeRepository likeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() { cleanup.truncateAllTables(); }

    @Test
    @DisplayName("같은 사용자-상품 관계를 두 번 저장하면 처음만 새로 생겼다고 알리고 관계는 한 건이다")
    void reportsOnlyNewRelation() {
        Like like = new Like(1, new ProductId(10));

        boolean first = likeRepository.save(like);
        boolean duplicate = likeRepository.save(like);

        assertThat(first).isTrue();
        assertThat(duplicate).isFalse();
        assertThat(jdbcTemplate.queryForObject("select count(*) from product_likes", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("있는 관계를 지우면 지웠다고 알리고 없는 관계를 지우면 지우지 않았다고 알린다")
    void reportsOnlyActualDeletion() {
        Like like = new Like(1, new ProductId(10));
        likeRepository.save(like);

        boolean deleted = likeRepository.delete(like);
        boolean missing = likeRepository.delete(like);

        assertThat(deleted).isTrue();
        assertThat(missing).isFalse();
        assertThat(jdbcTemplate.queryForObject("select count(*) from product_likes", Long.class)).isZero();
    }
}
