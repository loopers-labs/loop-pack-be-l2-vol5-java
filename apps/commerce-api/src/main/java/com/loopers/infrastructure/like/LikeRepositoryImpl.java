package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeModel;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.like.QLikeModel;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

@RequiredArgsConstructor
@Component
public class LikeRepositoryImpl implements LikeRepository {

    private static final QLikeModel LIKE = QLikeModel.likeModel;

    private final LikeJpaRepository likeJpaRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public void addIfAbsent(Long userId, Long productId, ZonedDateTime likedAt) {
        likeJpaRepository.insertIgnoringDuplicate(userId, productId, likedAt);
    }

    @Override
    public void remove(Long userId, Long productId) {
        likeJpaRepository.deleteByUserIdAndProductId(userId, productId);
    }

    /**
     * 최근에 누른 것부터 돌려준다. 좋아요는 수정되지 않으므로 id 역순이 곧 누른 시각 역순이다 (LIK-05).
     */
    @Override
    public List<LikeModel> findAllByUserIdNewestFirst(Long userId) {
        return queryFactory.selectFrom(LIKE)
            .where(LIKE.userId.eq(userId))
            .orderBy(LIKE.id.desc())
            .fetch();
    }
}
