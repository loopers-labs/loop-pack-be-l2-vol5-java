package com.loopers.infrastructure.persistence.shopping.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.shopping.model.Like;
import com.loopers.domain.shopping.repository.LikeRepository;
import com.loopers.infrastructure.persistence.shopping.entity.QLikeJpaEntity;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class LikeRepositoryIntegrationTest {
    private static final QLikeJpaEntity LIKE = QLikeJpaEntity.likeJpaEntity;

    @Autowired
    private LikeRepository likeRepository;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("좋아요 저장")
    @Nested
    class Save {
        @DisplayName("관계가 없으면 새로 저장한다")
        @Test
        void savesNewRelation() {
            boolean saved = likeRepository.save(Like.create(1L, 10L));

            assertThat(saved).isTrue();
            assertThat(countLikes(1L, 10L)).isEqualTo(1L);
        }

        @DisplayName("이미 저장된 관계는 예외 없이 false를 반환하고 기존 행을 바꾸지 않는다")
        @Test
        void ignoresDuplicate_andKeepsCreatedAt() {
            likeRepository.save(Like.create(1L, 10L));
            Instant before = createdAt(1L, 10L);

            boolean saved = likeRepository.save(Like.create(1L, 10L));

            assertThat(saved).isFalse();
            assertThat(countLikes(1L, 10L)).isEqualTo(1L);
            assertThat(createdAt(1L, 10L)).isEqualTo(before);
        }
    }

    @DisplayName("좋아요 삭제")
    @Nested
    class Delete {
        @DisplayName("존재하는 관계를 제거하고 true를 반환한다")
        @Test
        void removesExistingRelation() {
            likeRepository.save(Like.create(1L, 10L));

            boolean deleted = likeRepository.delete(1L, 10L);

            assertThat(deleted).isTrue();
            assertThat(countLikes(1L, 10L)).isZero();
        }

        @DisplayName("존재하지 않는 관계를 삭제하면 예외 없이 false를 반환한다")
        @Test
        void ignoresMissingRelation() {
            assertThat(likeRepository.delete(1L, 10L)).isFalse();
        }
    }

    private long countLikes(long userId, long productId) {
        return queryFactory.select(LIKE.count()).from(LIKE)
            .where(LIKE.userId.eq(userId), LIKE.productId.eq(productId))
            .fetchOne();
    }

    private Instant createdAt(long userId, long productId) {
        return queryFactory.select(LIKE.createdAt).from(LIKE)
            .where(LIKE.userId.eq(userId), LIKE.productId.eq(productId))
            .fetchOne();
    }
}
