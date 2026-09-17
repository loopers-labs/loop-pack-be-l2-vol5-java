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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 좋아요 관계의 저장·재조회. 영속성 컨텍스트 캐시가 아니라 DB에 반영된 값을 보도록 flush/clear 후 확인한다.
 */
@SpringBootTest
class LikeServiceIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long PRODUCT_ID = 10L;
    private static final Long OTHER_PRODUCT_ID = 20L;

    @Autowired
    private LikeService likeService;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @DisplayName("좋아요를 등록할 때, ")
    @Nested
    class Register {

        @DisplayName("사용자–상품 관계가 저장되고, flush·clear 후 다시 읽어도 같은 관계다. (LIK-001)")
        @Test
        void savesUserProductRelation() {
            transactionTemplate.executeWithoutResult(status -> {
                // act
                likeService.like(USER_ID, PRODUCT_ID);
                flushAndClear();

                // assert
                Like found = likeJpaRepository.findByUserIdAndProductId(USER_ID, PRODUCT_ID).orElseThrow();
                assertAll(
                    () -> assertThat(found.getUserId()).isEqualTo(USER_ID),
                    () -> assertThat(found.getProductId()).isEqualTo(PRODUCT_ID),
                    () -> assertThat(found.getCreatedAt()).isNotNull()
                );
            });
        }

        @DisplayName("같은 관계를 두 번 등록해도, DB에는 한 행만 남는다. (LIK-001, P-7)")
        @Test
        void keepsSingleRow_whenLikedTwice() {
            transactionTemplate.executeWithoutResult(status -> {
                // act
                likeService.like(USER_ID, PRODUCT_ID);
                flushAndClear();
                likeService.like(USER_ID, PRODUCT_ID);
                flushAndClear();

                // assert
                assertThat(likeJpaRepository.count()).isEqualTo(1L);
            });
        }

        @DisplayName("서비스를 거치지 않고 같은 관계를 저장하면, DB 유일 제약이 거절한다. (3-3 좋아요 중복 방지)")
        @Test
        void rejectsDuplicateRow_byUniqueConstraint() {
            // arrange
            likeJpaRepository.saveAndFlush(new Like(USER_ID, PRODUCT_ID));

            // act & assert
            assertThrows(DataIntegrityViolationException.class,
                () -> likeJpaRepository.saveAndFlush(new Like(USER_ID, PRODUCT_ID)));
            assertThat(likeJpaRepository.count()).isEqualTo(1L);
        }
    }

    @DisplayName("좋아요를 취소할 때, ")
    @Nested
    class Unlike {

        @DisplayName("관계 행이 물리 삭제되고, 같은 상품에 다시 좋아요할 수 있다. (P-8)")
        @Test
        void hardDeletesRow_andAllowsLikeAgain() {
            transactionTemplate.executeWithoutResult(status -> {
                // arrange
                likeService.like(USER_ID, PRODUCT_ID);
                flushAndClear();

                // act
                likeService.unlike(USER_ID, PRODUCT_ID);
                flushAndClear();

                // assert
                assertThat(likeJpaRepository.count()).isZero();

                likeService.like(USER_ID, PRODUCT_ID);
                flushAndClear();
                assertThat(likeJpaRepository.findByUserIdAndProductId(USER_ID, PRODUCT_ID)).isPresent();
            });
        }
    }

    @DisplayName("좋아요 수를 셀 때, ")
    @Nested
    class Count {

        @DisplayName("상품별 관계 수를 반환하고, 좋아요가 없는 상품은 결과에 없다. (LIK-002)")
        @Test
        void countsRelationsPerProduct() {
            transactionTemplate.executeWithoutResult(status -> {
                // arrange
                likeService.like(USER_ID, PRODUCT_ID);
                likeService.like(OTHER_USER_ID, PRODUCT_ID);
                likeService.like(USER_ID, OTHER_PRODUCT_ID);
                likeService.unlike(USER_ID, OTHER_PRODUCT_ID);
                flushAndClear();

                // act
                Map<Long, Long> counts = likeService.countLikes(List.of(PRODUCT_ID, OTHER_PRODUCT_ID));

                // assert
                assertAll(
                    () -> assertThat(counts).containsEntry(PRODUCT_ID, 2L),
                    () -> assertThat(counts).doesNotContainKey(OTHER_PRODUCT_ID),
                    () -> assertThat(likeService.countLikes(OTHER_PRODUCT_ID)).isZero()
                );
            });
        }
    }
}
