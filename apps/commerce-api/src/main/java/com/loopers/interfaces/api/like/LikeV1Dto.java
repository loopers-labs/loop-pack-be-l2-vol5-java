package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeInfo;

public class LikeV1Dto {

    public record LikeResponse(Long userId, Long productId, boolean liked) {
        public static LikeResponse from(LikeInfo info) {
            return new LikeResponse(info.userId(), info.productId(), info.liked());
        }
    }
}
