package com.loopers.interfaces.api.like;

public class LikeV1Dto {

    public record LikeResponse(Long productId, long likeCount, boolean liked) {
        public static LikeResponse of(Long productId, long likeCount, boolean liked) {
            return new LikeResponse(productId, likeCount, liked);
        }
    }
}
