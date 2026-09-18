package com.loopers.domain.like;

import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class LikeRepositoryTest {

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("사용자와 상품으로 좋아요를 조회할 때, ")
    @Nested
    class FindByUserIdAndProductId {
        @DisplayName("저장된 좋아요는, flush/clear 후 재조회해도 동일한 값으로 조회된다.")
        @Test
        void returnsLike_afterFlushAndClear() {
            // arrange
            likeRepository.save(new LikeModel(1L, 10L));
            entityManager.flush();
            entityManager.clear();

            // act
            Optional<LikeModel> result = likeRepository.findByUserIdAndProductId(1L, 10L);

            // assert
            assertThat(result).isPresent();
            assertThat(result.get().getProductId()).isEqualTo(10L);
        }

        @DisplayName("존재하지 않는 조합으로 조회하면, 빈 결과를 반환한다.")
        @Test
        void returnsEmpty_whenCombinationDoesNotExist() {
            // act
            Optional<LikeModel> result = likeRepository.findByUserIdAndProductId(999L, 999L);

            // assert
            assertThat(result).isEmpty();
        }
    }

    @DisplayName("상품별 좋아요 수를 셀 때, ")
    @Nested
    class CountActive {
        @DisplayName("삭제되지 않은 좋아요만 개수에 포함한다.")
        @Test
        void countsOnlyActiveLikes() {
            // arrange
            likeRepository.save(new LikeModel(1L, 10L));
            LikeModel cancelled = likeRepository.save(new LikeModel(2L, 10L));
            cancelled.delete();
            likeJpaRepository.save(cancelled);
            entityManager.flush();
            entityManager.clear();

            // act
            long result = likeRepository.countActiveByProductId(10L);

            // assert
            assertThat(result).isEqualTo(1L);
        }

        @DisplayName("여러 상품에 대해, 상품별로 그룹화된 개수를 반환한다.")
        @Test
        void returnsCountsGroupedByProduct() {
            // arrange
            likeRepository.save(new LikeModel(1L, 10L));
            likeRepository.save(new LikeModel(2L, 10L));
            likeRepository.save(new LikeModel(1L, 20L));
            entityManager.flush();
            entityManager.clear();

            // act
            Map<Long, Long> result = likeRepository.countActiveByProductIds(List.of(10L, 20L, 30L));

            // assert
            assertThat(result.get(10L)).isEqualTo(2L);
            assertThat(result.get(20L)).isEqualTo(1L);
            assertThat(result.get(30L)).isNull();
        }
    }
}
