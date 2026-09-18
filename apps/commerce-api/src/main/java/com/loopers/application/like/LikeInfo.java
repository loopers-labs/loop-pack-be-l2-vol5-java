package com.loopers.application.like;

import com.loopers.domain.like.Like;

public record LikeInfo(Long userId, Long productId, boolean liked) {

    public static LikeInfo from(Like like) {
        return new LikeInfo(like.getUserId(), like.getProductId(), true);
    }

    public static LikeInfo unliked(Long userId, Long productId) {
        return new LikeInfo(userId, productId, false);
    }
}
