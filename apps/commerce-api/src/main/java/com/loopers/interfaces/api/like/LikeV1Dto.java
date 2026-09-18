package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeInfo;

public class LikeV1Dto {
    public record LikeResponse(Long productId, boolean created) {}

    public record LikedProductResponse(Long productId, String productName, long price, long likeCount) {
        public static LikedProductResponse from(LikeInfo info) {
            return new LikedProductResponse(
                info.productId(),
                info.productName(),
                info.price(),
                info.likeCount()
            );
        }
    }
}
