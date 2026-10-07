package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeInfo;
import com.loopers.application.product.ProductInfo;
import com.loopers.interfaces.api.product.ProductV1Dto;

import java.util.List;

public class LikeV1Dto {
    public record LikeResponse(Long productId, long likeCount) {
        public static LikeResponse from(LikeInfo info) {
            return new LikeResponse(info.productId(), info.likeCount());
        }
    }

    public record MyLikesResponse(List<ProductV1Dto.ProductResponse> items) {
        public static MyLikesResponse from(List<ProductInfo> infos) {
            return new MyLikesResponse(infos.stream().map(ProductV1Dto.ProductResponse::from).toList());
        }
    }
}
