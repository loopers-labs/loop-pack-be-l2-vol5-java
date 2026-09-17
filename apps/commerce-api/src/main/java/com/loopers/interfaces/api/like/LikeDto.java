package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeInfo;

public class LikeDto {
    public record LikeResponse(Long productId, boolean liked, Long likeCount) {
        public static LikeResponse from(LikeInfo info) {
            return new LikeResponse(info.productId(), info.liked(), info.likeCount());
        }
    }
}
