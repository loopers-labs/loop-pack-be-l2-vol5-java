package com.loopers.infrastructure.like;

import com.loopers.domain.like.Like;

final class LikeJpaMapper {

    private LikeJpaMapper() {}

    static Like toDomain(LikeJpaEntity entity) {
        return Like.create(entity.getUserId(), entity.getProductId());
    }

    static LikeJpaEntity toNewEntity(Like like) {
        return LikeJpaEntity.create(like.getUserId(), like.getProductId());
    }
}
