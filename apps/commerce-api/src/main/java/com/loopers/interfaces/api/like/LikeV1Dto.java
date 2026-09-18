package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeInfo;

public class LikeV1Dto {

    public record LikeResponse(Long userId, Long productId, boolean liked) {
        public static LikeResponse from(LikeInfo info) {
            return new LikeResponse(info.userId(), info.productId(), info.liked());
        }
    }

    public record MyLikeProductResponse(Long productId, Long brandId, String brandName, String name, long price, long likeCount) {
        public static MyLikeProductResponse from(com.loopers.application.product.CustomerProductInfo info) {
            return new MyLikeProductResponse(info.id(), info.brandId(), info.brandName(), info.name(), info.price(), info.likeCount());
        }
    }
}
