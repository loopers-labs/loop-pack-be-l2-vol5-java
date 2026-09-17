package com.loopers.interfaces.api.like;

import com.loopers.domain.like.LikeModel;

public class LikeV1Dto {

    public record LikeResponse(Long id, Long userId, Long productId) {
        public static LikeResponse from(LikeModel like) {
            return new LikeResponse(like.getId(), like.getUserId(), like.getProductId());
        }
    }
}
